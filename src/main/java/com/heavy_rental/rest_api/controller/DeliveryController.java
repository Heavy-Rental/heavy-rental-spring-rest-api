package com.heavy_rental.rest_api.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.heavy_rental.rest_api.dto.DeliveryItemResponse;
import com.heavy_rental.rest_api.dto.StatusUpdateRequest;
import com.heavy_rental.rest_api.service.DeliveryService;

@RestController
@RequestMapping("/api/deliveries")
public class DeliveryController {

    private final DeliveryService deliveryService;

    public DeliveryController(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @GetMapping
    public List<DeliveryItemResponse> getTodaysDeliveries() {
        return deliveryService.getTodaysDeliveries();
    }

    @PatchMapping("/{bookingId}/status")
    public DeliveryItemResponse updateStatus(@PathVariable Long bookingId, @RequestBody StatusUpdateRequest request) {
        return deliveryService.updateStatus(bookingId, request.bookingStatus());
    }
}