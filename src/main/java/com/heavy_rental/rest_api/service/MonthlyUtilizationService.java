package com.heavy_rental.rest_api.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.heavy_rental.rest_api.dto.MonthlyUtilizationResponse;
import com.heavy_rental.rest_api.entity.Booking;
import com.heavy_rental.rest_api.entity.BookingItem;
import com.heavy_rental.rest_api.entity.Payment;
import com.heavy_rental.rest_api.repository.AssetRepository;
import com.heavy_rental.rest_api.repository.BookingItemRepository;
import com.heavy_rental.rest_api.repository.PaymentRepository;

@Service
public class MonthlyUtilizationService {

    private static final List<Booking.BookingStatus> ACTIVE_STATUSES = List.of(
            Booking.BookingStatus.CONFIRMED, Booking.BookingStatus.MOBILISED, Booking.BookingStatus.COMPLETED);

    private final PaymentRepository paymentRepository;
    private final BookingItemRepository bookingItemRepository;
    private final AssetRepository assetRepository;

    public MonthlyUtilizationService(PaymentRepository paymentRepository,
                                      BookingItemRepository bookingItemRepository,
                                      AssetRepository assetRepository) {
        this.paymentRepository = paymentRepository;
        this.bookingItemRepository = bookingItemRepository;
        this.assetRepository = assetRepository;
    }

    @Transactional(readOnly = true)
    public List<MonthlyUtilizationResponse> getTrailingSixMonths() {
        List<Payment> successfulPayments = paymentRepository.findByStatus(Payment.PaymentStatus.SUCCESS);
        List<BookingItem> activeBookingItems = bookingItemRepository.findByBookingStatusIn(ACTIVE_STATUSES);
        long totalAssets = assetRepository.count();

        LocalDate today = LocalDate.now();
        List<MonthlyUtilizationResponse> result = new ArrayList<>();

        for (int monthsAgo = 5; monthsAgo >= 0; monthsAgo--) {
            LocalDate monthDate = today.minusMonths(monthsAgo);
            LocalDate monthStart = monthDate.withDayOfMonth(1);
            LocalDate monthEnd = monthStart.plusMonths(1).minusDays(1);

            BigDecimal revenue = successfulPayments.stream()
                    .filter(p -> p.getPaidAt() != null)
                    .filter(p -> {
                        LocalDate paidDate = p.getPaidAt().toLocalDate();
                        return !paidDate.isBefore(monthStart) && !paidDate.isAfter(monthEnd);
                    })
                    .map(Payment::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            long bookedAssetDays = activeBookingItems.stream()
                    .mapToLong(item -> overlapDays(item.getBooking(), monthStart, monthEnd))
                    .sum();

            long daysInMonth = monthStart.lengthOfMonth();
            double utilization = totalAssets == 0
                    ? 0.0
                    : (bookedAssetDays * 100.0) / (totalAssets * daysInMonth);

            String monthLabel = monthDate.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            long id = 6 - monthsAgo;

            result.add(new MonthlyUtilizationResponse(id, monthLabel, utilization, revenue));
        }

        return result;
    }

    private long overlapDays(Booking booking, LocalDate monthStart, LocalDate monthEnd) {
        LocalDate overlapStart = booking.getStartDate().isAfter(monthStart) ? booking.getStartDate() : monthStart;
        LocalDate overlapEnd = booking.getEndDate().isBefore(monthEnd) ? booking.getEndDate() : monthEnd;
        long days = ChronoUnit.DAYS.between(overlapStart, overlapEnd) + 1;
        return Math.max(days, 0);
    }
}
