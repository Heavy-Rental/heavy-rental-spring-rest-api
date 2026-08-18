package com.heavy_rental.rest_api.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "return_records")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
public class ReturnRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id")
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id")
    private User driver;

    @Column(name = "returned_at")
    private LocalDateTime returnedAt;

    @Column(name = "return_photos")
    private String returnPhotos;

    @Column(name = "customer_signature_url")
    private String customerSignatureUrl;
}
