package com.heavy_rental.rest_api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.heavy_rental.rest_api.dto.MonthlyUtilizationResponse;
import com.heavy_rental.rest_api.entity.Booking;
import com.heavy_rental.rest_api.entity.BookingItem;
import com.heavy_rental.rest_api.entity.Payment;
import com.heavy_rental.rest_api.repository.AssetRepository;
import com.heavy_rental.rest_api.repository.BookingItemRepository;
import com.heavy_rental.rest_api.repository.PaymentRepository;

/**
 * One-time manual verification, not meant to stay in the suite: recomputes
 * revenue/utilization independently from raw repository data (different code
 * path than MonthlyUtilizationService) and diffs against the real endpoint's
 * output for the actual seeded data. Delete this file once confirmed.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MonthlyUtilizationAccuracyTest {

    private static final List<Booking.BookingStatus> ACTIVE_STATUSES = List.of(
            Booking.BookingStatus.CONFIRMED, Booking.BookingStatus.MOBILISED, Booking.BookingStatus.COMPLETED);

    @Autowired
    private MonthlyUtilizationService monthlyUtilizationService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private BookingItemRepository bookingItemRepository;

    @Autowired
    private AssetRepository assetRepository;

    @Test
    void trailingSixMonthsMatchIndependentlyComputedValues() {
        List<MonthlyUtilizationResponse> actual = monthlyUtilizationService.getTrailingSixMonths();
        assertEquals(6, actual.size(), "expected 6 trailing months");

        List<Payment> successfulPayments = paymentRepository.findAll().stream()
                .filter(p -> p.getStatus() == Payment.PaymentStatus.SUCCESS)
                .filter(p -> p.getPaidAt() != null)
                .toList();

        List<BookingItem> activeItems = bookingItemRepository.findAll().stream()
                .filter(item -> ACTIVE_STATUSES.contains(item.getBooking().getStatus()))
                .toList();

        long totalAssets = assetRepository.count();
        LocalDate today = LocalDate.now();

        for (int monthsAgo = 5; monthsAgo >= 0; monthsAgo--) {
            int index = 5 - monthsAgo;
            MonthlyUtilizationResponse row = actual.get(index);

            LocalDate monthStart = today.minusMonths(monthsAgo).withDayOfMonth(1);
            LocalDate monthEnd = monthStart.plusMonths(1).minusDays(1);

            BigDecimal expectedRevenue = successfulPayments.stream()
                    .filter(p -> {
                        LocalDate paidDate = p.getPaidAt().toLocalDate();
                        return !paidDate.isBefore(monthStart) && !paidDate.isAfter(monthEnd);
                    })
                    .map(Payment::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            long expectedBookedAssetDays = activeItems.stream()
                    .mapToLong(item -> daysOverlapping(item.getBooking(), monthStart, monthEnd))
                    .sum();
            long daysInMonth = monthStart.lengthOfMonth();
            double expectedUtilization = totalAssets == 0
                    ? 0.0
                    : (expectedBookedAssetDays * 100.0) / (totalAssets * daysInMonth);

            System.out.printf(
                    "%-4s expected revenue=%s actual revenue=%s | expected util=%.4f actual util=%.4f%n",
                    row.month(), expectedRevenue, row.revenue(), expectedUtilization, row.utilization());

            assertEquals(0, expectedRevenue.compareTo(row.revenue()),
                    () -> row.month() + ": revenue mismatch, expected " + expectedRevenue + " got " + row.revenue());
            assertEquals(expectedUtilization, row.utilization(), 0.0001,
                    row.month() + ": utilization mismatch");
        }
    }

    // Deliberately a fresh implementation (not lifted from MonthlyUtilizationService)
    // of "days of [monthStart, monthEnd] covered by [booking.start, booking.end]".
    private long daysOverlapping(Booking booking, LocalDate monthStart, LocalDate monthEnd) {
        LocalDate latestStart = booking.getStartDate().isAfter(monthStart) ? booking.getStartDate() : monthStart;
        LocalDate earliestEnd = booking.getEndDate().isBefore(monthEnd) ? booking.getEndDate() : monthEnd;
        if (latestStart.isAfter(earliestEnd)) {
            return 0;
        }
        return ChronoUnit.DAYS.between(latestStart, earliestEnd) + 1;
    }
}
