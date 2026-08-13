package com.heavy_rental.rest_api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
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

  // --- S2b: haystack session handles (Call 1 persist → Call 2/3 use) ---

  /** Haystack ingest id from Call 1; required handle for Call 2 recommend and Call 3 Q&amp;A. */
  @Column(name = "ingest_id")
  private String ingestId;

  /** Identity sent to haystack (typically String.valueOf(user.id) or Call 1 echo). */
  @Column(name = "haystack_user_id")
  private String haystackUserId;

  /** Logical submit key (audit); same key reused if ingest is retried. */
  @Column(name = "idempotency_key")
  private String idempotencyKey;

  /** Shared with outbound haystack calls for log join. */
  @Column(name = "correlation_id")
  private String correlationId;

  @Column(name = "tentative_start_date")
  private LocalDate tentativeStartDate;

  @Column(name = "tentative_end_date")
  private LocalDate tentativeEndDate;

  @Column(name = "expected_budget_amount", precision = 19, scale = 2)
  private BigDecimal expectedBudgetAmount;

  @Column(name = "expected_budget_currency")
  private String expectedBudgetCurrency;

  @Column(name = "expected_budget_source")
  private String expectedBudgetSource;

  @Column(name = "warnings", columnDefinition = "TEXT")
  private String warnings;
}
