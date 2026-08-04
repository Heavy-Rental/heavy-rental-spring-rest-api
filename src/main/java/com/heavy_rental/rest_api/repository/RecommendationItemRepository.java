package com.heavy_rental.rest_api.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.heavy_rental.rest_api.entity.RecommendationItem;

public interface RecommendationItemRepository extends JpaRepository<RecommendationItem, Long> {
  List<RecommendationItem> findByRecommendationId(Long recommendationId);
}
