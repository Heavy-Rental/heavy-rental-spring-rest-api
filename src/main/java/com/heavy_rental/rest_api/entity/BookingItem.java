package com.heavy_rental.rest_api.entity;

import com.heavy_rental.rest_api.enums.ConditionType;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "booking_items")
@Getter 
@Setter
@NoArgsConstructor 
@AllArgsConstructor
public class BookingItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id")
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id")
    private Asset asset;

    @Column(name = "daily_rate", precision = 10, scale = 2)
    private Double dailyRate;

    @Column(name = "subtotal", precision = 10, scale = 2)
    private Double subtotal;

    @Column(name = "start_engine_hours")
    private Double startEngineHours;

    @Column(name = "end_engine_hours")
    private Double endEngineHours;

    // Shared Enum
    @Enumerated(EnumType.STRING)
    @Column(name = "initial_condition")
    private ConditionType initialCondition;

    // Shared Enum
    @Enumerated(EnumType.STRING)
    @Column(name = "return_condition")
    private ConditionType returnCondition;
}
