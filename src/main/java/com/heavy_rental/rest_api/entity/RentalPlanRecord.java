package com.heavy_rental.rest_api.entity;

import java.math.BigDecimal;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "rental_plan_records")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
public class RentalPlanRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rental_plan_id")
    private RentalPlan rentalPlan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id")
    private Asset asset;

    @Column(name = "daily_rate")
    private BigDecimal dailyRate;

    @Column(name = "subtotal")
    private BigDecimal subtotal;
}
