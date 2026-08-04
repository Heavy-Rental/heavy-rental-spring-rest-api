package com.heavy_rental.rest_api.entity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.heavy_rental.rest_api.enums.ConditionType;

@Entity
@Table(name = "assets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Asset {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 100)
  private String name;

  @Column(nullable = false, length = 255)
  private String serialno;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "category_id", nullable = false)
  private AssetCategory category;
  
  @Column(name = "capacity", precision = 10, scale = 2)
  private Integer capacity;

  @Column(name = "platform_height", precision = 10, scale = 2)
  private BigDecimal platformHeight;

  @Column(length = 255)
  private String description;

  @Column(name = "base_daily_rate", nullable = false, precision = 10, scale = 2)
  private BigDecimal baseDailyRate;

  @Column(name = "min_daily_rate", nullable = false, precision = 10, scale = 2)
  private BigDecimal minDailyRate;

  @Column(name = "max_daily_rate", nullable = false, precision = 10, scale = 2)
  private BigDecimal maxDailyRate;

  @Enumerated(EnumType.STRING)
  private ConditionType condition;

  @Column(name = "last_condition_updated_at")
  private LocalDateTime lastConditionUpdatedAt;

  @Column(name = "purchase_year")
  private Integer purchaseYear;

}
