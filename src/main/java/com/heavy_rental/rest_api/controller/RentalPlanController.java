package com.heavy_rental.rest_api.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import com.heavy_rental.rest_api.dto.RentalPlanCreateRequest;
import com.heavy_rental.rest_api.dto.RentalPlanResponse;
import com.heavy_rental.rest_api.service.RentalPlanService;

import com.heavy_rental.rest_api.dto.RentalPlanItemRequest;

@RestController
@RequestMapping("/api/rental-plans")
public class RentalPlanController {

    private final RentalPlanService rentalPlanService;

    public RentalPlanController(RentalPlanService rentalPlanService) {
        this.rentalPlanService = rentalPlanService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RentalPlanResponse create(@RequestBody RentalPlanCreateRequest request, @AuthenticationPrincipal Jwt jwt) {
        return rentalPlanService.create(request, jwt.getSubject());
    }

    @GetMapping
    public List<RentalPlanResponse> listMine(@AuthenticationPrincipal Jwt jwt) {
        return rentalPlanService.listMine(jwt.getSubject());
    }

    @GetMapping("/{id}")
    public RentalPlanResponse getById(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return rentalPlanService.getById(id, jwt.getSubject());
    }

    @PostMapping("/{id}/items")
    @ResponseStatus(HttpStatus.CREATED)
    public RentalPlanResponse addItem(
            @PathVariable Long id, @RequestBody RentalPlanItemRequest request, @AuthenticationPrincipal Jwt jwt) {
        return rentalPlanService.addItem(id, request, jwt.getSubject());
    }

    @DeleteMapping("/{id}/items/{itemId}")
    public RentalPlanResponse removeItem(
            @PathVariable Long id, @PathVariable Long itemId, @AuthenticationPrincipal Jwt jwt) {
        return rentalPlanService.removeItem(id, itemId, jwt.getSubject());
    }

    @PostMapping("/{id}/quote")
    public RentalPlanResponse requestQuote(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return rentalPlanService.requestQuote(id, jwt.getSubject());
    }

}
