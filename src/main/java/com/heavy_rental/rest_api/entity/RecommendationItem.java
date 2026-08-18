package com.heavy_rental.rest_api.entity;

import java.math.BigDecimal;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "recommendation_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationItem {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "recommendation_id")
  private AIRecommendation recommendation;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "asset_id")
  private Asset asset;

  @Column(name = "rank_order")
  private Integer rankOrder;

  @Column(name = "match_score")
  private BigDecimal matchScore;

  @Column(name = "ml_predicted_price")
  private BigDecimal mlPredictedPrice;
  
}
