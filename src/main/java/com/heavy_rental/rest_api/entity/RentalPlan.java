package com.heavy_rental.rest_api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "rental_plan")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RentalPlan {
  
  public enum PlanStatus {
    DRAFT, SAVED, QUOTED, CONVERTED
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Version
  private Long version;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "customer_id", nullable = false)
  private User customer;

  @Column(name = "start_date")
  private LocalDate startDate;

  @Column(name = "end_date")
  private LocalDate endDate;

  @Column(name = "total_amount")
  private BigDecimal totalAmount;

  @Enumerated(EnumType.STRING)
  private PlanStatus status;

  @Column(name = "site_address")
  private String siteAddress;

  @Column(name = "site_postal_code")
  private String sitePostalCode;

  @Column(name = "site_latitude")
  private BigDecimal siteLatitude;

  @Column(name = "site_longitude")
  private BigDecimal siteLongitude;

  @Column(name = "created_at")
  private LocalDateTime createdAt;

  @Column(name = "updated_at")
  private LocalDateTime updatedAt;
}
