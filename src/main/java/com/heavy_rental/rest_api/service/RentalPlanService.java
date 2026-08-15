package com.heavy_rental.rest_api.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TransactionTemplate readOnlyTransactionTemplate;
    private final TransactionTemplate writeTransactionTemplate;

    public RentalPlanService(
            RentalPlanRepository rentalPlanRepository,
            RentalPlanRecordRepository rentalPlanRecordRepository,
            UserRepository userRepository,
            AssetRepository assetRepository,
            PricingClient pricingClient,
            DynamicPricingService dynamicPricingService,
            PlatformTransactionManager transactionManager) {
        this.rentalPlanRepository = rentalPlanRepository;
        this.rentalPlanRecordRepository = rentalPlanRecordRepository;
        this.userRepository = userRepository;
        this.assetRepository = assetRepository;
        this.pricingClient = pricingClient;
        this.dynamicPricingService = dynamicPricingService;
        this.readOnlyTransactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate.setReadOnly(true);
        this.writeTransactionTemplate = new TransactionTemplate(transactionManager);
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

    /**
     * Deliberately NOT {@code @Transactional} at this level. {@link DynamicPricingService} makes
     * a blocking HTTP call to haystack-fast-api (up to {@code haystack.timeouts.pricing-read},
     * 20s by default, plus retry) — if that call happened inside a single DB transaction spanning
     * the whole method, the {@code rental_plan} row's optimistic lock (@Version) would stay
     * "checked out" for the whole call. Since the same row is written by addItem/removeItem/
     * cancel/checkout too, any of those landing during that window would win the race and bump
     * {@code version}, so this transaction's final save would fail with
     * {@code ObjectOptimisticLockingFailureException} — surfaced to the customer as a 409 even
     * though nothing was actually wrong with their request (HR-153).
     * <p>
     * Split into two short transactions with the slow, untransacted haystack call in between:
     * read the plan/items, price them, then reload-and-write. This shrinks the lock window from
     * ~20s to milliseconds without changing the pricing/fallback semantics.
     */
    public RentalPlanResponse requestQuote(Long planId, String customerEmail, String correlationId) {
        QuoteContext context = readOnlyTransactionTemplate.execute(status -> loadQuoteContext(planId, customerEmail));

        Map<Long, PricingClient.ItemPrice> priceByItemId = null;
        if (dynamicPricingService.isEnabled()) {
            List<PricingClient.ItemPrice> prices =
                    dynamicPricingService.priceItems(context.plan(), context.items(), correlationId);
            priceByItemId = new LinkedHashMap<>();
            for (int i = 0; i < context.items().size(); i++) {
                priceByItemId.put(context.items().get(i).getId(), prices.get(i));
            }
        }

        Map<Long, PricingClient.ItemPrice> finalPriceByItemId = priceByItemId;
        return writeTransactionTemplate.execute(status -> finalizeQuote(planId, customerEmail, finalPriceByItemId));
    }

    private record QuoteContext(RentalPlan plan, List<RentalPlanRecord> items) {
    }

    /**
     * Re-quoting a QUOTED plan is allowed — it's how a customer refreshes a stale quote
     * (BookingService's 24-hour freshness check, REQ-6) before checkout. Only a CONVERTED
     * plan is truly final and can never be quoted again.
     */
    private QuoteContext loadQuoteContext(Long planId, String customerEmail) {
        RentalPlan plan = loadOwnedPlan(planId, customerEmail);
        if (plan.getStatus() == RentalPlan.PlanStatus.CONVERTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Plan has already been converted to a booking");
        }

        List<RentalPlanRecord> items = rentalPlanRecordRepository.findByRentalPlanId(plan.getId());
        if (items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot quote an empty plan");
        }

        // Force-init each item's Asset now, while the read transaction's session is still open —
        // DynamicPricingService's per-item fallback (DefaultPricingClient) reads
        // Asset.baseDailyRate after this transaction has closed.
        items.forEach(item -> item.getAsset().getBaseDailyRate());

        return new QuoteContext(plan, items);
    }

    /**
     * Reloads the plan/items fresh (picking up the current {@code version}) and applies the
     * dynamic prices computed between the two transactions, keyed by item id so a concurrent
     * cart edit that changed the item set in between doesn't misapply prices to the wrong item.
     */
    private RentalPlanResponse finalizeQuote(
            Long planId, String customerEmail, Map<Long, PricingClient.ItemPrice> priceByItemId) {
        RentalPlan plan = loadOwnedPlan(planId, customerEmail);
        if (plan.getStatus() == RentalPlan.PlanStatus.CONVERTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Plan has already been converted to a booking");
        }

        List<RentalPlanRecord> items = rentalPlanRecordRepository.findByRentalPlanId(plan.getId());
        if (items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot quote an empty plan");
        }

        if (priceByItemId != null) {
            for (RentalPlanRecord item : items) {
                PricingClient.ItemPrice price = priceByItemId.get(item.getId());
                if (price != null) {
                    item.setDailyRate(price.dailyRate());
                    item.setSubtotal(price.subtotal());
                    rentalPlanRecordRepository.save(item);
                }
            }
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
