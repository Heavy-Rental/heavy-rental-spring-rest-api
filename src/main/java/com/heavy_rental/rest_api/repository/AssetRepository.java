package com.heavy_rental.rest_api.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import com.heavy_rental.rest_api.entity.Asset;
import com.heavy_rental.rest_api.enums.ConditionType;
import java.util.List;


public interface AssetRepository extends JpaRepository<Asset, Long> {
  List<Asset> findByCategoryId(Long categoryId);
  List<Asset> findByNameContainingIgnoreCase(String name);
  List<Asset> findByCondition(ConditionType condition);

  @Query("SELECT a FROM Asset a JOIN FETCH a.category")
  List<Asset> findAllWithCategory();
}
