package com.heavy_rental.rest_api.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import com.heavy_rental.rest_api.dto.AssetImageRequest;
import com.heavy_rental.rest_api.dto.AssetRequest;
import com.heavy_rental.rest_api.dto.AssetResponse;
import com.heavy_rental.rest_api.service.AssetService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/assets")
public class AssetController {

    private final AssetService assetService;

    public AssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    @GetMapping
    public List<AssetResponse> browse(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String condition,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate endDate) {
        return assetService.browse(category, search, condition, startDate, endDate);
    }

    @GetMapping("/{id}")
    public AssetResponse getById(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate endDate) {
        return assetService.getById(id, startDate, endDate);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AssetResponse create(@Valid @RequestBody AssetRequest request) {
        return assetService.create(request);
    }

    @PutMapping("/{id}")
    public AssetResponse replace(@PathVariable Long id, @Valid @RequestBody AssetRequest request) {
        return assetService.replace(id, request);
    }

    @PatchMapping("/{id}")
    public AssetResponse patch(@PathVariable Long id, @RequestBody AssetRequest request) {
        return assetService.patch(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        assetService.delete(id);
    }

    @PutMapping("/{id}/image")
    public AssetResponse uploadImage(@PathVariable Long id, @Valid @RequestBody AssetImageRequest request) {
        return assetService.uploadImage(id, request);
    }
}
