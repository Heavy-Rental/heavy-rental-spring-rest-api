package com.heavy_rental.rest_api.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Pattern;

/**
 * {@code siteAddress} is optional (see {@code openspec/changes/pricing-postal-distance/}
 * "Follow-on: optional siteAddress at plan creation") — the "Skip for now" cart flow needs a plan
 * to persist before an address is chosen. {@code @Pattern} alone (no {@code @NotBlank}) already
 * does the right thing per Bean Validation semantics: {@code null} always passes; an empty or
 * malformed value still fails the regex and is rejected exactly as before.
 */
public record RentalPlanCreateRequest(
        LocalDate startDate,
        LocalDate endDate,
        @Pattern(
                regexp = "^.*\\d{6}$",
                message = "Site address must end with a 6-digit postal code, e.g. \"20 Jurong Port Road, 619094\"")
        String siteAddress) {

    public RentalPlanCreateRequest {
        siteAddress = siteAddress == null ? null : siteAddress.strip();
    }
}
