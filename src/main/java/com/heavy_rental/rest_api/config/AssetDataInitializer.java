package com.heavy_rental.rest_api.config;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.heavy_rental.rest_api.entity.Asset;
import com.heavy_rental.rest_api.entity.AssetCategory;
import com.heavy_rental.rest_api.entity.AssetImage;
import com.heavy_rental.rest_api.enums.ConditionType;
import com.heavy_rental.rest_api.repository.AssetCategoryRepository;
import com.heavy_rental.rest_api.repository.AssetImageRepository;
import com.heavy_rental.rest_api.repository.AssetRepository;

/**
 * Seeds mock asset catalog data (categories, assets, images) when the asset_categories table is empty (local/dev convenience).
 */
@Component
public class AssetDataInitializer implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AssetDataInitializer.class);
	private static final String MOCK_IMAGES_DIR = "mock-images/";

	private final AssetCategoryRepository categoryRepository;
	private final AssetRepository assetRepository;
	private final AssetImageRepository imageRepository;

	public AssetDataInitializer(
			AssetCategoryRepository categoryRepository,
			AssetRepository assetRepository,
			AssetImageRepository imageRepository) {
		this.categoryRepository = categoryRepository;
		this.assetRepository = assetRepository;
		this.imageRepository = imageRepository;
	}

	@Override
	public void run(ApplicationArguments args) throws IOException {
		if (categoryRepository.count() > 0) {
			return;
		}

		AssetCategory excavator = categoryRepository.save(
				category("Excavator", "Tracked and wheeled excavators for digging and earthmoving"));
		AssetCategory scissorsLift = categoryRepository.save(
				category("Scissors Lift", "Vertical-access aerial work platforms"));
		AssetCategory boomLift = categoryRepository.save(
				category("Boom Lift", "Articulating and telescopic aerial work platforms"));
		AssetCategory forkLift = categoryRepository.save(
				category("Fork Lift", "Warehouse and yard material-handling forklifts"));

		Asset catExcavator = assetRepository.save(asset("CAT 320 Excavator", "SN-EXC-000320", excavator,
				null, null, "20-ton tracked excavator, quick-hitch bucket",
				new BigDecimal("450.00"), new BigDecimal("380.00"), new BigDecimal("520.00"),
				ConditionType.GOOD, 2021));

		Asset komatsuExcavator = assetRepository.save(asset("Komatsu PC210 Excavator", "SN-EXC-000210", excavator,
				null, null, "21-ton tracked excavator with hydraulic thumb",
				new BigDecimal("470.00"), new BigDecimal("400.00"), new BigDecimal("540.00"),
				ConditionType.EXCELLENT, 2023));

		Asset genieScissor = assetRepository.save(asset("Genie GS-1930 Scissor Lift", "SN-SCL-001930", scissorsLift,
				null, new BigDecimal("7.80"), "19ft electric scissor lift, indoor/outdoor",
				new BigDecimal("120.00"), new BigDecimal("90.00"), new BigDecimal("150.00"),
				ConditionType.EXCELLENT, 2022));

		Asset jlgScissor = assetRepository.save(asset("JLG 2630ES Scissor Lift", "SN-SCL-002630", scissorsLift,
				null, new BigDecimal("9.75"), "26ft electric scissor lift",
				new BigDecimal("140.00"), new BigDecimal("110.00"), new BigDecimal("170.00"),
				ConditionType.FAIR, 2018));

		Asset jlgBoom = assetRepository.save(asset("JLG 460SJ Boom Lift", "SN-BML-000460", boomLift,
				null, new BigDecimal("15.72"), "46ft telescopic boom lift, 4WD",
				new BigDecimal("210.00"), new BigDecimal("180.00"), new BigDecimal("260.00"),
				ConditionType.GOOD, 2020));

		Asset genieBoom = assetRepository.save(asset("Genie Z-45 Boom Lift", "SN-BML-000045", boomLift,
				null, new BigDecimal("13.70"), "45ft articulating boom lift",
				new BigDecimal("195.00"), new BigDecimal("160.00"), new BigDecimal("240.00"),
				ConditionType.NEEDS_REPAIR, 2017));

		Asset toyotaForklift = assetRepository.save(asset("Toyota 8FD25 Forklift", "SN-FKL-008FD25", forkLift,
				2500, null, "2.5-ton diesel counterbalance forklift",
				new BigDecimal("150.00"), new BigDecimal("120.00"), new BigDecimal("180.00"),
				ConditionType.GOOD, 2021));

		Asset hysterForklift = assetRepository.save(asset("Hyster H2.5FT Forklift", "SN-FKL-H25FT", forkLift,
				2500, null, "2.5-ton cushion-tire forklift",
				new BigDecimal("160.00"), new BigDecimal("130.00"), new BigDecimal("190.00"),
				ConditionType.EXCELLENT, 2023));

		saveImage(catExcavator, "asset1-cat320-excavator-a.jpg");
		saveImage(catExcavator, "asset1-cat320-excavator-b.jpg");
		saveImage(komatsuExcavator, "asset2-komatsu-pc210-excavator.jpg");
		saveImage(genieScissor, "asset4-genie-gs1930-scissorlift.jpg");
		saveImage(jlgScissor, "asset5-jlg-2630es-scissorlift.jpg");
		saveImage(jlgBoom, "asset6-jlg-460sj-boomlift.jpg");
		saveImage(genieBoom, "asset7-genie-z45-boomlift.jpg");
		saveImage(toyotaForklift, "asset7-toyota-8fd25-forklift.jpg");
		saveImage(hysterForklift, "asset8-hyster-h25ft-forklift.jpg");

		log.info("Seeded asset mock data: {} categories, {} assets, {} images",
				categoryRepository.count(), assetRepository.count(), imageRepository.count());
	}

	private AssetCategory category(String name, String description) {
		AssetCategory category = new AssetCategory();
		category.setName(name);
		category.setDescription(description);
		return category;
	}

	private Asset asset(String name, String serialno, AssetCategory category, Integer capacity,
			BigDecimal platformHeight, String description, BigDecimal baseDailyRate, BigDecimal minDailyRate,
			BigDecimal maxDailyRate, ConditionType condition, Integer purchaseYear) {
		Asset asset = new Asset();
		asset.setName(name);
		asset.setSerialno(serialno);
		asset.setCategory(category);
		asset.setCapacity(capacity);
		asset.setPlatformHeight(platformHeight);
		asset.setDescription(description);
		asset.setBaseDailyRate(baseDailyRate);
		asset.setMinDailyRate(minDailyRate);
		asset.setMaxDailyRate(maxDailyRate);
		asset.setCondition(condition);
		asset.setLastConditionUpdatedAt(LocalDateTime.now());
		asset.setPurchaseYear(purchaseYear);
		return asset;
	}

	private void saveImage(Asset asset, String filename) throws IOException {
		AssetImage image = new AssetImage();
		image.setAsset(asset);
		image.setImage(readAsBase64(filename));
		image.setUploadedAt(LocalDateTime.now());
		imageRepository.save(image);
	}

	private String readAsBase64(String filename) throws IOException {
		ClassPathResource resource = new ClassPathResource(MOCK_IMAGES_DIR + filename);
		try (InputStream inputStream = resource.getInputStream()) {
			return Base64.getEncoder().encodeToString(inputStream.readAllBytes());
		}
	}
}
