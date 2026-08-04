package com.heavy_rental.rest_api.entity;

import jakarta.persistence.*;
import lombok.*;
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
        PENDING, CONFIRMED, MOBILISED, COMPLETED, CANCELLED
    }

    public enum PaidStatus {
        DEPOSIT, FULL, UNPAID
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

    @Column(name = "total_amount", precision = 10, scale = 2)
    private Double totalAmount;

    @Column(name = "deposit_amount", precision = 10, scale = 2)
    private Double depositAmount;

    @Column(name = "remaining_balance", precision = 10, scale = 2)
    private Double remainingBalance;

    @Enumerated(EnumType.STRING)
    @Column(name = "paid_status")
    private PaidStatus paidStatus;

    @Column(name = "site_address")
    private String siteAddress;

    @Column(name = "site_postal_code")
    private String sitePostalCode;

    @Column(name = "site_latitude")
    private Double siteLatitude;

    @Column(name = "site_longitude")
    private Double siteLongitude;

    @Column(name = "delivery_notes", length = 500)
    private String deliveryNotes;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
