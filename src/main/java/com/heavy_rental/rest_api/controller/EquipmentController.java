package com.heavy_rental.rest_api.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import com.heavy_rental.rest_api.dto.EquipmentRequest;
import com.heavy_rental.rest_api.dto.EquipmentResponse;
import com.heavy_rental.rest_api.service.AssetService;

@RestController
@RequestMapping("/api/equipment")
public class EquipmentController {

    private final AssetService assetService;

    public EquipmentController(AssetService assetService) {
        this.assetService = assetService;
    }

    @GetMapping
    public List<EquipmentResponse> browse(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String condition,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate endDate) {
        return assetService.browse(category, search, condition, startDate, endDate);
    }

    @GetMapping("/{id}")
    public EquipmentResponse getById(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate endDate) {
        return assetService.getById(id, startDate, endDate);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EquipmentResponse create(@RequestBody EquipmentRequest request) {
        return assetService.create(request);
    }

    @PutMapping("/{id}")
    public EquipmentResponse replace(@PathVariable Long id, @RequestBody EquipmentRequest request) {
        return assetService.replace(id, request);
    }

    @PatchMapping("/{id}")
    public EquipmentResponse patch(@PathVariable Long id, @RequestBody EquipmentRequest request) {
        return assetService.patch(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        assetService.delete(id);
    }
}
