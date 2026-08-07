package com.heavy_rental.rest_api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

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

    @Column(name = "site_postal_code")
    private String sitePostalCode;

    @Column(name = "site_latitude")
    private BigDecimal siteLatitude;

    @Column(name = "site_longitude")
    private BigDecimal siteLongitude;

    @Column(name = "delivery_notes", length = 500)
    private String deliveryNotes;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
