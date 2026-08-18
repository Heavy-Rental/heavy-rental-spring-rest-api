package com.heavy_rental.rest_api.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.heavy_rental.rest_api.dto.PostalCodeValidationResponse;
import com.heavy_rental.rest_api.service.PostalCodeService;

/**
 * Real-time postal code validation for the web portal's site-address form (see
 * {@code openspec/changes/pricing-postal-distance/}) — standalone rather than nested under
 * {@code /api/rentalPlans} since the same postal-code-in-siteAddress need exists for both the
 * rental-plan and booking forms. Falls under the default {@code SecurityConfig} auth rule
 * ({@code anyRequest().hasAnyAuthority("ROLE_USER","ROLE_ADMIN")}) — no explicit rule needed here.
 */
@RestController
@RequestMapping("/api/postalCodes")
public class PostalCodeController {

    private final PostalCodeService postalCodeService;

    public PostalCodeController(PostalCodeService postalCodeService) {
        this.postalCodeService = postalCodeService;
    }

    @GetMapping("/{postalCode}")
    public ResponseEntity<PostalCodeValidationResponse> validate(@PathVariable String postalCode) {
        return postalCodeService.validate(postalCode);
    }
}
