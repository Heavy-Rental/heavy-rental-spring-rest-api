package com.heavy_rental.rest_api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.hibernate.annotations.Formula;

@Entity
@Table(name = "bookings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Booking {

    public enum BookingStatus {
        PENDING_DEPOSIT, PENDING_CONFIRMED, CONFIRMED, MOBILISED, COMPLETED, CANCELLED
    }

    /**
     * Statuses that hold an asset as unavailable for other bookings over its date range —
     * shared by AssetService's availability check and BookingService's create-time
     * conflict check so the two can't drift apart on what "already booked" means.
     */
    public static final List<BookingStatus> ACTIVE_STATUSES =
            List.of(BookingStatus.PENDING_DEPOSIT, BookingStatus.PENDING_CONFIRMED,
                    BookingStatus.CONFIRMED, BookingStatus.MOBILISED);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private User customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rental_plan_id")
    private RentalPlan rentalPlan;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    private BookingStatus status;

    @Column(name = "total_amount")
    private BigDecimal totalAmount;

    @Column(name = "deposit_amount")
    private BigDecimal depositAmount;

    @Column(name = "remaining_balance")
    private BigDecimal remainingBalance;

    @Column(name = "site_address")
    private String siteAddress;

    @Setter(AccessLevel.NONE)
    @Formula("substring(site_address from length(site_address) - 5 for 6)")
    private String sitePostalCode;

    @Column(name = "site_latitude")
    private BigDecimal siteLatitude;

    @Column(name = "site_longitude")
    private BigDecimal siteLongitude;

    @Column(name = "delivery_notes", length = 500)
    private String deliveryNotes;

    @Column(name = "return_notes", length = 500)
    private String returnNotes;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "balance_charge_attempted_at")
    private LocalDateTime balanceChargeAttemptedAt;

    @Column(name = "needs_manual_follow_up", columnDefinition = "boolean default false")
    private boolean needsManualFollowUp;
}
