package com.heavy_rental.rest_api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "ai_recommendations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AIRecommendation {

  public enum RecommendationStatus {
    GENERATED, ACCEPTED, REJECTED, EXPIRED
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id")
  private User user;

  @Column(name = "confidence_score", precision = 10, scale = 2)
  private BigDecimal confidenceScore;

  @Enumerated(EnumType.STRING)
  private RecommendationStatus status;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "previous_recommendation_id")
  private AIRecommendation previousRecommendation;

  @Column(name = "raw_project_prompt", columnDefinition = "TEXT")
  private String rawProjectPrompt;

  @Column(name = "document_url")
  private String documentUrl;

  @Column(name = "ai_reasoning_summary", columnDefinition = "TEXT")
  private String aiReasoningSummary;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

}
