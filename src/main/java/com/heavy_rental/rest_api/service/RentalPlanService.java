package com.heavy_rental.rest_api.service;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.heavy_rental.rest_api.dto.RentalPlanCreateRequest;
import com.heavy_rental.rest_api.dto.RentalPlanItemResponse;
import com.heavy_rental.rest_api.dto.RentalPlanResponse;
import com.heavy_rental.rest_api.entity.RentalPlan;
import com.heavy_rental.rest_api.entity.User;
import com.heavy_rental.rest_api.repository.RentalPlanRecordRepository;
import com.heavy_rental.rest_api.repository.RentalPlanRepository;
import com.heavy_rental.rest_api.repository.UserRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.heavy_rental.rest_api.dto.RentalPlanItemRequest;
import com.heavy_rental.rest_api.entity.Asset;
import com.heavy_rental.rest_api.entity.RentalPlanRecord;
import com.heavy_rental.rest_api.repository.AssetRepository;

import org.springframework.transaction.annotation.Transactional;

@Service
public class RentalPlanService {

    private static final List<RentalPlan.PlanStatus> ACTIVE_STATUSES = List.of(RentalPlan.PlanStatus.DRAFT,
            RentalPlan.PlanStatus.SAVED, RentalPlan.PlanStatus.QUOTED);

    private final RentalPlanRepository rentalPlanRepository;
    private final RentalPlanRecordRepository rentalPlanRecordRepository;
    private final UserRepository userRepository;
    private final AssetRepository assetRepository;
    private final PricingClient pricingClient;
    private final DynamicPricingService dynamicPricingService;

    public RentalPlanService(
            RentalPlanRepository rentalPlanRepository,
            RentalPlanRecordRepository rentalPlanRecordRepository,
            UserRepository userRepository,
            AssetRepository assetRepository,
            PricingClient pricingClient,
            DynamicPricingService dynamicPricingService) {
        this.rentalPlanRepository = rentalPlanRepository;
        this.rentalPlanRecordRepository = rentalPlanRecordRepository;
        this.userRepository = userRepository;
        this.assetRepository = assetRepository;
        this.pricingClient = pricingClient;
        this.dynamicPricingService = dynamicPricingService;
    }

    @Transactional
    public RentalPlanResponse create(RentalPlanCreateRequest request, String customerEmail) {
        User customer = resolveCustomer(customerEmail);

        boolean hasActivePlan = rentalPlanRepository.findByCustomerId(customer.getId()).stream()
                .anyMatch(plan -> ACTIVE_STATUSES.contains(plan.getStatus()));
        if (hasActivePlan) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You already have an active rental plan");
        }

        RentalPlan plan = new RentalPlan();
        plan.setCustomer(customer);
        plan.setStartDate(request.startDate());
        plan.setEndDate(request.endDate());
        plan.setSiteAddress(request.siteAddress());
        plan.setStatus(RentalPlan.PlanStatus.DRAFT);
        plan.setCreatedAt(LocalDateTime.now());

        rentalPlanRepository.save(plan);
        return toResponse(plan);
    }

    @Transactional(readOnly = true)
    public List<RentalPlanResponse> listMine(String customerEmail) {
        User customer = resolveCustomer(customerEmail);
        return rentalPlanRepository.findByCustomerId(customer.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public RentalPlanResponse getById(Long id, String customerEmail) {
        return toResponse(loadOwnedPlan(id, customerEmail));
    }

    @Transactional
    public RentalPlanResponse addItem(Long planId, RentalPlanItemRequest request, String customerEmail) {
        RentalPlan plan = loadOwnedPlan(planId, customerEmail);

        Asset asset = assetRepository.findById(request.assetId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown assetId"));

        PricingClient.ItemPrice price = pricingClient.priceItem(asset, plan.getStartDate(), plan.getEndDate());

        RentalPlanRecord item = new RentalPlanRecord();
        item.setRentalPlan(plan);
        item.setAsset(asset);
        item.setDailyRate(price.dailyRate());
        item.setSubtotal(price.subtotal());
        rentalPlanRecordRepository.save(item);

        revertQuoteIfNeeded(plan);
        return toResponse(plan);
    }

    @Transactional
    public RentalPlanResponse removeItem(Long planId, Long itemId, String customerEmail) {
        RentalPlan plan = loadOwnedPlan(planId, customerEmail);

        RentalPlanRecord item = rentalPlanRecordRepository.findById(itemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Line item not found"));

        if (!item.getRentalPlan().getId().equals(plan.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Line item not found");
        }

        rentalPlanRecordRepository.delete(item);

        revertQuoteIfNeeded(plan);
        return toResponse(plan);
    }

    /**
     * A QUOTED plan's frozen totalAmount only reflects the item set at the moment it was
     * quoted. If the cart changes afterward, that price is stale — rather than leaving a
     * QUOTED plan whose price doesn't match its items, revert to DRAFT (clearing the stale
     * total) and require a fresh quote before checkout. Deliberate reversal of the previous
     * "items locked once quoted" behavior — see SPEC-rental-plan-quote.md REQ-2/REQ-3.
     */
    private void revertQuoteIfNeeded(RentalPlan plan) {
        if (plan.getStatus() == RentalPlan.PlanStatus.QUOTED) {
            plan.setStatus(RentalPlan.PlanStatus.DRAFT);
            plan.setTotalAmount(null);
            plan.setUpdatedAt(LocalDateTime.now());
            rentalPlanRepository.save(plan);
        }
    }

    @Transactional
    public RentalPlanResponse requestQuote(Long planId, String customerEmail, String correlationId) {
        RentalPlan plan = loadOwnedPlan(planId, customerEmail);

        // Re-quoting a QUOTED plan is allowed — it's how a customer refreshes a stale quote
        // (BookingService's 24-hour freshness check, REQ-6) before checkout. Only a CONVERTED
        // plan is truly final and can never be quoted again.
        if (plan.getStatus() == RentalPlan.PlanStatus.CONVERTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Plan has already been converted to a booking");
        }

        List<RentalPlanRecord> items = rentalPlanRecordRepository.findByRentalPlanId(plan.getId());
        if (items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot quote an empty plan");
        }

        if (dynamicPricingService.isEnabled()) {
            repriceItemsDynamically(plan, items, correlationId);
        }

        BigDecimal total = items.stream()
                .map(RentalPlanRecord::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        plan.setTotalAmount(total);
        plan.setStatus(RentalPlan.PlanStatus.QUOTED);
        plan.setUpdatedAt(LocalDateTime.now());
        rentalPlanRepository.save(plan);

        return toResponse(plan);
    }

    /**
     * Refreshes each item's dailyRate/subtotal from {@link DynamicPricingService} immediately
     * before summing into totalAmount (see openspec/changes/dynamic-plan-quote-pricing/).
     * Never blocks the quote — DynamicPricingService falls back to base-rate arithmetic per
     * item on any pricing-service failure.
     */
    private void repriceItemsDynamically(RentalPlan plan, List<RentalPlanRecord> items, String correlationId) {
        List<PricingClient.ItemPrice> prices = dynamicPricingService.priceItems(plan, items, correlationId);
        for (int i = 0; i < items.size(); i++) {
            RentalPlanRecord item = items.get(i);
            PricingClient.ItemPrice price = prices.get(i);
            item.setDailyRate(price.dailyRate());
            item.setSubtotal(price.subtotal());
            rentalPlanRecordRepository.save(item);
        }
    }

    @Transactional
    public RentalPlanResponse cancel(Long planId, String customerEmail) {
        RentalPlan plan = loadOwnedPlan(planId, customerEmail);

        if (plan.getStatus() == RentalPlan.PlanStatus.CONVERTED) {
            throw new RentalPlanConflictException("already_converted",
                    "Rental plan has already been converted to a booking and cannot be cancelled");
        }
        if (plan.getStatus() == RentalPlan.PlanStatus.CANCELLED) {
            throw new RentalPlanConflictException("already_cancelled", "Rental plan has already been cancelled");
        }

        plan.setStatus(RentalPlan.PlanStatus.CANCELLED);
        plan.setTotalAmount(null);
        plan.setUpdatedAt(LocalDateTime.now());
        rentalPlanRepository.save(plan);

        return toResponse(plan);
    }

    private RentalPlan loadOwnedPlan(Long planId, String customerEmail) {
        User customer = resolveCustomer(customerEmail);
        RentalPlan plan = rentalPlanRepository.findById(planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Rental plan not found"));

        if (!plan.getCustomer().getId().equals(customer.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Rental plan not found");
        }
        return plan;
    }

    private User resolveCustomer(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown user"));
    }

    private RentalPlanResponse toResponse(RentalPlan plan) {
        List<RentalPlanItemResponse> items = rentalPlanRecordRepository.findByRentalPlanId(plan.getId()).stream()
                .map(item -> new RentalPlanItemResponse(
                        item.getId(),
                        item.getAsset().getId(),
                        item.getAsset().getName(),
                        item.getDailyRate(),
                        item.getSubtotal()))
                .toList();

        return new RentalPlanResponse(
                plan.getId(), plan.getStartDate(), plan.getEndDate(), plan.getSiteAddress(),
                plan.getStatus().name(), plan.getTotalAmount(), items,
                plan.getUpdatedAt(), plan.getCreatedAt());


                
    }
}
