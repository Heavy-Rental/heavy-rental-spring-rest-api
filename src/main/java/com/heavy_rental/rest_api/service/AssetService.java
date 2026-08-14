package com.heavy_rental.rest_api.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.heavy_rental.rest_api.dto.AssetImageRequest;
import com.heavy_rental.rest_api.dto.AssetRequest;
import com.heavy_rental.rest_api.dto.AssetResponse;
import com.heavy_rental.rest_api.entity.Asset;
import com.heavy_rental.rest_api.entity.AssetCategory;
import com.heavy_rental.rest_api.entity.AssetImage;
import com.heavy_rental.rest_api.entity.Booking;
import com.heavy_rental.rest_api.entity.BookingItem;
import com.heavy_rental.rest_api.enums.ConditionType;
import com.heavy_rental.rest_api.repository.AssetCategoryRepository;
import com.heavy_rental.rest_api.repository.AssetImageRepository;
import com.heavy_rental.rest_api.repository.AssetRepository;
import com.heavy_rental.rest_api.repository.BookingItemRepository;

@Service
public class AssetService {

    private static final String JPEG_DATA_URI_PREFIX = "data:image/jpeg;base64,";
    private static final int MAX_IMAGE_BASE64_LENGTH = 7_000_000;

    private final AssetRepository assetRepository;
    private final AssetCategoryRepository assetCategoryRepository;
    private final AssetImageRepository assetImageRepository;
    private final BookingItemRepository bookingItemRepository;

    public AssetService(AssetRepository assetRepository,
                         AssetCategoryRepository assetCategoryRepository,
                         AssetImageRepository assetImageRepository,
                         BookingItemRepository bookingItemRepository) {
        this.assetRepository = assetRepository;
        this.assetCategoryRepository = assetCategoryRepository;
        this.assetImageRepository = assetImageRepository;
        this.bookingItemRepository = bookingItemRepository;
    }

    @Transactional(readOnly = true)
    public List<AssetResponse> browse(String category, String search, String condition,
                                       LocalDate startDate, LocalDate endDate) {
        List<Asset> assets;
        if (category != null) {
            AssetCategory found = assetCategoryRepository.findByName(category);
            if (found == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown category: " + category);
            }
            assets = assetRepository.findByCategoryId(found.getId());
        } else {
            assets = assetRepository.findAllWithCategory();
        }

        if (search != null && !search.isBlank()) {
            String needle = search.toLowerCase();
            assets = assets.stream()
                    .filter(a -> a.getName().toLowerCase().contains(needle))
                    .toList();
        }

        if (condition != null) {
            ConditionType conditionType = parseCondition(condition);
            assets = assets.stream()
                    .filter(a -> a.getCondition() == conditionType)
                    .toList();
        }

        LocalDate[] window = resolveAvailabilityWindow(startDate, endDate);
        List<Long> assetIds = assets.stream().map(Asset::getId).toList();
        Map<Long, AssetImage> imageByAssetId = loadImageByAssetId(assetIds);
        Set<Long> unavailableAssetIds = findUnavailableAssetIds(assetIds, window);
        Map<Long, Double> utilizationByAssetId = computeUtilizationByAssetId(assetIds);

        return assets.stream()
                .map(asset -> toResponse(asset,
                        imageByAssetId.get(asset.getId()),
                        window == null ? null : !unavailableAssetIds.contains(asset.getId()),
                        utilizationByAssetId.get(asset.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public AssetResponse getById(Long id, LocalDate startDate, LocalDate endDate) {
        Asset asset = assetRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));

        LocalDate[] window = resolveAvailabilityWindow(startDate, endDate);
        AssetImage image = firstImage(id);
        Set<Long> unavailableAssetIds = findUnavailableAssetIds(List.of(id), window);
        Double utilization = computeUtilizationByAssetId(List.of(id)).get(id);

        return toResponse(asset, image, window == null ? null : !unavailableAssetIds.contains(id), utilization);
    }

    @Transactional
    public AssetResponse create(AssetRequest request) {
        if (assetRepository.existsByName(request.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset name already in use: " + request.name());
        }
        AssetCategory category = resolveCategory(request.categoryId());

        Asset asset = new Asset();
        applyRequest(asset, request, category);
        if (asset.getCondition() != null) {
            asset.setLastConditionUpdatedAt(LocalDateTime.now());
        }
        Asset saved = assetRepository.save(asset);

        // A brand-new asset can't have any bookings yet — no query needed, unlike replace/patch below.
        return toResponse(saved, null, true, 0.0);
    }

    @Transactional
    public AssetResponse replace(Long id, AssetRequest request) {
        Asset asset = assetRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));
        if (assetRepository.existsByNameAndIdNot(request.name(), id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset name already in use: " + request.name());
        }
        AssetCategory category = resolveCategory(request.categoryId());
        ConditionType oldCondition = asset.getCondition();
        applyRequest(asset, request, category);
        if (!Objects.equals(oldCondition, asset.getCondition())) {
            asset.setLastConditionUpdatedAt(LocalDateTime.now());
        }
        Asset saved = assetRepository.save(asset);

        Double utilization = computeUtilizationByAssetId(List.of(id)).get(id);
        return toResponse(saved, firstImage(id), true, utilization);
    }

    @Transactional
    public AssetResponse patch(Long id, AssetRequest request) {
        Asset asset = assetRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));

        if (request.name() != null) {
            if (!request.name().equals(asset.getName())
                    && assetRepository.existsByNameAndIdNot(request.name(), id)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Asset name already in use: " + request.name());
            }
            asset.setName(request.name());
        }
        if (request.serialno() != null) asset.setSerialno(request.serialno());
        if (request.categoryId() != null) asset.setCategory(resolveCategory(request.categoryId()));
        if (request.capacity() != null) asset.setCapacity(request.capacity());
        if (request.platformHeight() != null) asset.setPlatformHeight(request.platformHeight());
        if (request.desc() != null) asset.setDescription(request.desc());
        if (request.baseDailyRate() != null) asset.setBaseDailyRate(request.baseDailyRate());
        if (request.minDailyRate() != null) asset.setMinDailyRate(request.minDailyRate());
        if (request.maxDailyRate() != null) asset.setMaxDailyRate(request.maxDailyRate());
        if (request.condition() != null) {
            ConditionType parsed = parseCondition(request.condition());
            if (parsed != asset.getCondition()) {
                asset.setLastConditionUpdatedAt(LocalDateTime.now());
            }
            asset.setCondition(parsed);
        }
        if (request.purchaseYear() != null) asset.setPurchaseYear(request.purchaseYear());
        if (request.location() != null) asset.setLocation(request.location());

        Asset saved = assetRepository.save(asset);
        Double utilization = computeUtilizationByAssetId(List.of(id)).get(id);
        return toResponse(saved, firstImage(id), true, utilization);
    }

    @Transactional
    public void delete(Long id) {
        if (!assetRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found");
        }

        List<AssetImage> images = assetImageRepository.findByAssetId(id);
        if (!images.isEmpty()) {
            assetImageRepository.deleteAll(images);
        }

        try {
            assetRepository.deleteById(id);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Asset has associated bookings/records and cannot be deleted");
        }
    }

    @Transactional
    public AssetResponse uploadImage(Long id, AssetImageRequest request) {
        Asset asset = assetRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));

        if (request.image() == null || request.image().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "image is required");
        }
        if (request.image().length() > MAX_IMAGE_BASE64_LENGTH) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "image payload too large");
        }

        List<AssetImage> existing = assetImageRepository.findByAssetId(id);
        if (!existing.isEmpty()) {
            assetImageRepository.deleteAll(existing);
        }

        AssetImage image = new AssetImage();
        image.setAsset(asset);
        image.setImage(request.image());
        image.setUploadedAt(LocalDateTime.now());
        AssetImage saved = assetImageRepository.save(image);

        Double utilization = computeUtilizationByAssetId(List.of(id)).get(id);
        return toResponse(asset, saved, true, utilization);
    }

    private AssetCategory resolveCategory(Long categoryId) {
        if (categoryId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "categoryId is required");
        }
        return assetCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unknown categoryId: " + categoryId));
    }

    private void applyRequest(Asset asset, AssetRequest request, AssetCategory category) {
        asset.setName(request.name());
        asset.setSerialno(request.serialno());
        asset.setCategory(category);
        asset.setCapacity(request.capacity());
        asset.setPlatformHeight(request.platformHeight());
        asset.setDescription(request.desc());
        asset.setBaseDailyRate(request.baseDailyRate());
        asset.setMinDailyRate(request.minDailyRate());
        asset.setMaxDailyRate(request.maxDailyRate());
        asset.setCondition(request.condition() != null ? parseCondition(request.condition()) : null);
        asset.setPurchaseYear(request.purchaseYear());
        asset.setLocation(request.location());
    }

    private ConditionType parseCondition(String condition) {
        try {
            return ConditionType.valueOf(condition.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown condition: " + condition);
        }
    }

    private LocalDate[] resolveAvailabilityWindow(LocalDate startDate, LocalDate endDate) {
        if (startDate == null && endDate == null) {
            return null;
        }
        if (startDate == null || endDate == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Both startDate and endDate must be provided together");
        }
        return new LocalDate[] { startDate, endDate };
    }

    private Map<Long, AssetImage> loadImageByAssetId(List<Long> assetIds) {
        if (assetIds.isEmpty()) {
            return Map.of();
        }
        return assetImageRepository.findByAssetIdIn(assetIds).stream()
                .collect(Collectors.toMap(image -> image.getAsset().getId(),
                        Function.identity(),
                        (first, second) -> first));
    }

    private AssetImage firstImage(Long assetId) {
        return assetImageRepository.findByAssetId(assetId).stream().findFirst().orElse(null);
    }

    private Set<Long> findUnavailableAssetIds(List<Long> assetIds, LocalDate[] window) {
        if (assetIds.isEmpty() || window == null) {
            return Set.of();
        }
        return bookingItemRepository.findAssetIdsWithOverlappingBooking(
                assetIds, window[0], window[1], Booking.ACTIVE_STATUSES);
    }

    /**
     * Per-asset utilization for the current month: booked days (Booking.UTILIZATION_STATUSES,
     * same day-overlap math as MonthlyUtilizationService's fleet-wide figure) divided by days
     * in the current month, as a percentage. Missing from the map (rather than 0.0) only when
     * assetIds is empty; every requested asset id gets an entry, 0.0 if it has no bookings.
     */
    private Map<Long, Double> computeUtilizationByAssetId(List<Long> assetIds) {
        if (assetIds.isEmpty()) {
            return Map.of();
        }
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = monthStart.plusMonths(1).minusDays(1);
        long daysInMonth = monthStart.lengthOfMonth();

        List<BookingItem> utilizedItems = bookingItemRepository.findByBookingStatusIn(Booking.UTILIZATION_STATUSES);
        Set<Long> assetIdSet = Set.copyOf(assetIds);

        Map<Long, Long> bookedDaysByAssetId = utilizedItems.stream()
                .filter(item -> assetIdSet.contains(item.getAsset().getId()))
                .collect(Collectors.groupingBy(
                        item -> item.getAsset().getId(),
                        Collectors.summingLong(item -> Booking.overlapDays(item.getBooking(), monthStart, monthEnd))));

        return assetIds.stream()
                .collect(Collectors.toMap(
                        Function.identity(),
                        id -> (bookedDaysByAssetId.getOrDefault(id, 0L) * 100.0) / daysInMonth));
    }

    private String toDataUri(AssetImage image) {
        return image != null ? JPEG_DATA_URI_PREFIX + image.getImage() : null;
    }

    private AssetResponse toResponse(Asset asset, AssetImage image, Boolean available, Double utilization) {
        return new AssetResponse(
                asset.getId(),
                asset.getName(),
                asset.getCategory().getName(),
                asset.getBaseDailyRate(),
                asset.getMinDailyRate(),
                asset.getMaxDailyRate(),
                asset.getCapacity(),
                asset.getPlatformHeight(),
                asset.getPurchaseYear(),
                asset.getCondition() != null ? asset.getCondition().name() : null,
                available,
                asset.getDescription(),
                toDataUri(image),
                asset.getLocation(),
                List.of(),
                asset.getSerialno(),
                asset.getLastConditionUpdatedAt(),
                utilization);

    }
}
