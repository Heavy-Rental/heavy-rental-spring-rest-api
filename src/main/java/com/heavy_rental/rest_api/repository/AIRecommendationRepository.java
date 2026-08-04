package com.heavy_rental.rest_api.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.heavy_rental.rest_api.entity.AIRecommendation;

public interface AIRecommendationRepository extends JpaRepository<AIRecommendation, Long> {
  List<AIRecommendation> findByUserId(Long userId);
  List<AIRecommendation> findByStatus(AIRecommendation.RecommendationStatus status);
}
