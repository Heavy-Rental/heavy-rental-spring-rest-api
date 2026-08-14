package com.heavy_rental.rest_api.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.heavy_rental.rest_api.dto.BookingResponse;
import com.heavy_rental.rest_api.dto.BookingUpdateRequest;
import com.heavy_rental.rest_api.dto.CreateBookingItemRequest;
import com.heavy_rental.rest_api.dto.CreateBookingRequest;
import com.heavy_rental.rest_api.entity.Asset;
import com.heavy_rental.rest_api.entity.Booking;
import com.heavy_rental.rest_api.entity.BookingItem;
import com.heavy_rental.rest_api.entity.RentalPlan;
import com.heavy_rental.rest_api.entity.RentalPlanRecord;
import com.heavy_rental.rest_api.entity.User;
import com.heavy_rental.rest_api.mapper.BookingMapper;
import com.heavy_rental.rest_api.repository.AssetRepository;
import com.heavy_rental.rest_api.repository.BookingItemRepository;
import com.heavy_rental.rest_api.repository.BookingRepository;
import com.heavy_rental.rest_api.repository.RentalPlanRecordRepository;
import com.heavy_rental.rest_api.repository.RentalPlanRepository;

@Service
public class BookingService {

    /**
     * The customer's up-front deposit as a fraction of the booking total. This is the
     * single source of truth for the deposit rate — PaymentService only ever reads the
     * persisted Booking.depositAmount this produces, it never computes a rate itself
     * (see openspec/specs/payments-stripe/).
     */
    private static final BigDecimal DEPOSIT_RATE = new BigDecimal("0.30");

    /** How long a RentalPlan's quoted price stays valid before checkout requires a re-quote. */
    private static final Duration QUOTE_VALIDITY = Duration.ofHours(24);

    private final BookingRepository bookingRepository;
    private final BookingItemRepository bookingItemRepository;
    private final AssetRepository assetRepository;
    private final RentalPlanRepository rentalPlanRepository;
    private final RentalPlanRecordRepository rentalPlanRecordRepository;
    private final CurrentUserService currentUserService;
    private final BookingMapper mapper;

    public BookingService(
            BookingRepository bookingRepository,
            BookingItemRepository bookingItemRepository,
            AssetRepository assetRepository,
            RentalPlanRepository rentalPlanRepository,
            RentalPlanRecordRepository rentalPlanRecordRepository,
            CurrentUserService currentUserService,
            BookingMapper mapper) {
        this.bookingRepository = bookingRepository;
        this.bookingItemRepository = bookingItemRepository;
        this.assetRepository = assetRepository;
        this.rentalPlanRepository = rentalPlanRepository;
        this.rentalPlanRecordRepository = rentalPlanRecordRepository;
        this.currentUserService = currentUserService;
        this.mapper = mapper;
    }

    @Transactional
    public BookingResponse createBooking(Jwt jwt, CreateBookingRequest request) {
        User customer = currentUserService.getUser(jwt);

        if (request.rentalPlanId() != null) {
            return createFromRentalPlan(customer, request);
        }

        if (request.items() == null || request.items().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one item is required");
        }
        if (request.startDate() == null || request.endDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startDate and endDate are required");
        }
        if (!request.endDate().isAfter(request.startDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endDate must be after startDate");
        }

        List<Asset> assets = new ArrayList<>();
        for (CreateBookingItemRequest itemRequest : request.items()) {
            if (itemRequest.assetId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "assetId is required for every item");
            }
            Asset asset = assetRepository.findById(itemRequest.assetId())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "Asset not found: " + itemRequest.assetId()));
            assets.add(asset);
        }

        assertAvailable(assets, request.startDate(), request.endDate());

        // Inclusive of both start and end day — matches DefaultPricingClient's quote-time math,
        // so a booking's total agrees with the RentalPlan it was quoted from.
        long days = ChronoUnit.DAYS.between(request.startDate(), request.endDate()) + 1;

        BigDecimal totalAmount = BigDecimal.ZERO;
        List<BookingItem> bookingItems = new ArrayList<>();
        for (Asset asset : assets) {
            BigDecimal dailyRate = asset.getBaseDailyRate();
            BigDecimal subtotal = dailyRate.multiply(BigDecimal.valueOf(days)).setScale(2, RoundingMode.HALF_UP);
            totalAmount = totalAmount.add(subtotal);

            BookingItem bookingItem = new BookingItem();
            bookingItem.setAsset(asset);
            bookingItem.setDailyRate(dailyRate);
            bookingItem.setSubtotal(subtotal);
            bookingItems.add(bookingItem);
        }

        return persistBooking(customer, null, request.startDate(), request.endDate(), totalAmount, bookingItems, request);
    }

    /**
     * Checkout from a quoted RentalPlan (the cart → quote → checkout flow): items, dates, and
     * price all come from the plan's own persisted records — never recomputed or taken from the
     * request body — so the amount charged always matches what the customer was quoted.
     */
    private BookingResponse createFromRentalPlan(User customer, CreateBookingRequest request) {
        RentalPlan rentalPlan = rentalPlanRepository.findById(request.rentalPlanId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Rental plan not found"));

        // 404, not 403 — matches this codebase's existing "don't confirm another customer's
        // resource exists" convention (SPEC-rental-plan-quote.md REQ-3).
        if (!rentalPlan.getCustomer().getId().equals(customer.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Rental plan not found");
        }
        if (rentalPlan.getStatus() != RentalPlan.PlanStatus.QUOTED) {
            throw new RentalPlanConflictException("quote_not_ready",
                    "Rental plan must be quoted before checkout");
        }
        boolean stale = rentalPlan.getUpdatedAt() == null
                || Duration.between(rentalPlan.getUpdatedAt(), LocalDateTime.now()).compareTo(QUOTE_VALIDITY) > 0;
        if (stale) {
            throw new RentalPlanConflictException("quote_expired",
                    "Quote has expired — request a new quote before checking out");
        }

        List<RentalPlanRecord> records = rentalPlanRecordRepository.findByRentalPlanId(rentalPlan.getId());
        List<Asset> assets = records.stream().map(RentalPlanRecord::getAsset).toList();

        // Availability was never held at quote time (SPEC-rental-plan-quote.md §7), so it must
        // still be checked here, right before the booking actually commits.
        assertAvailable(assets, rentalPlan.getStartDate(), rentalPlan.getEndDate());

        List<BookingItem> bookingItems = records.stream().map(record -> {
            BookingItem bookingItem = new BookingItem();
            bookingItem.setAsset(record.getAsset());
            bookingItem.setDailyRate(record.getDailyRate());
            bookingItem.setSubtotal(record.getSubtotal());
            return bookingItem;
        }).toList();

        BookingResponse response = persistBooking(customer, rentalPlan, rentalPlan.getStartDate(),
                rentalPlan.getEndDate(), rentalPlan.getTotalAmount(), bookingItems, request);

        rentalPlan.setStatus(RentalPlan.PlanStatus.CONVERTED);
        rentalPlanRepository.save(rentalPlan);

        return response;
    }

    // Same conflict check AssetService's browse/getById availability flag uses
    // (Booking.ACTIVE_STATUSES) — reject up front rather than silently double-booking.
    private void assertAvailable(List<Asset> assets, LocalDate startDate, LocalDate endDate) {
        List<Long> assetIds = assets.stream().map(Asset::getId).toList();
        Set<Long> unavailable = bookingItemRepository.findAssetIdsWithOverlappingBooking(
                assetIds, startDate, endDate, Booking.ACTIVE_STATUSES);
        if (!unavailable.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Asset(s) already booked for the requested dates: " + unavailable);
        }
    }

    private BookingResponse persistBooking(User customer, RentalPlan rentalPlan, LocalDate startDate,
            LocalDate endDate, BigDecimal totalAmount, List<BookingItem> bookingItems, CreateBookingRequest request) {
        BigDecimal depositAmount = totalAmount.multiply(DEPOSIT_RATE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal remainingBalance = totalAmount.subtract(depositAmount);

        Booking booking = new Booking();
        booking.setCustomer(customer);
        booking.setRentalPlan(rentalPlan);
        booking.setStartDate(startDate);
        booking.setEndDate(endDate);
        booking.setStatus(Booking.BookingStatus.PENDING_DEPOSIT);
        booking.setTotalAmount(totalAmount);
        booking.setDepositAmount(depositAmount);
        booking.setRemainingBalance(remainingBalance);
        booking.setSiteAddress(request.siteAddress());
        booking.setDeliveryNotes(request.deliveryNotes());
        booking.setCreatedAt(LocalDateTime.now());

        Booking savedBooking = bookingRepository.save(booking);

        bookingItems.forEach(item -> item.setBooking(savedBooking));
        bookingItemRepository.saveAll(bookingItems);

        return toResponse(savedBooking);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> getBookings() {
        return bookingRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public BookingResponse getBooking(Long bookingId) {
        return toResponse(findByIdOr404(bookingId));
    }

    @Transactional
    public BookingResponse updateBooking(Long bookingId, BookingUpdateRequest request) {
        Booking booking = findByIdOr404(bookingId);

        booking.setStartDate(request.startDate());
        booking.setEndDate(request.endDate());
        booking.setSiteAddress(request.siteAddress());
        booking.setDeliveryNotes(request.deliveryNotes());

        bookingRepository.save(booking);
        return toResponse(booking);
    }

    @Transactional
    public BookingResponse updateStatus(Long bookingId, String requestedStatus) {
        Booking booking = findByIdOr404(bookingId);
        Booking.BookingStatus status = parseStatusOr400(requestedStatus);
        booking.setStatus(status);
        bookingRepository.save(booking);
        return toResponse(booking);
    }

    private BookingResponse toResponse(Booking booking) {
        return mapper.toBookingResponse(booking, bookingItemRepository.findByBookingId(booking.getId()));
    }

    Booking findByIdOr404(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found: " + bookingId));
    }

    static Booking.BookingStatus parseStatusOr400(String status) {
        try {
            return Booking.BookingStatus.valueOf(status);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid bookingStatus: " + status);
        }
    }
}
