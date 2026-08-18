package com.heavy_rental.rest_api.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import com.heavy_rental.rest_api.entity.AssetImage;

public interface AssetImageRepository extends JpaRepository<AssetImage, Long> {
  List<AssetImage> findByAssetId(Long assetId);
  List<AssetImage> findByAssetIdIn(Collection<Long> assetIds);
}
