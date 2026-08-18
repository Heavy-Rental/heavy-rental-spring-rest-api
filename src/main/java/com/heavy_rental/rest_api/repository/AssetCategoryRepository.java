package com.heavy_rental.rest_api.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.heavy_rental.rest_api.entity.AssetCategory;

public interface AssetCategoryRepository extends JpaRepository<AssetCategory, Long> {
    AssetCategory findByName(String name);
}
