package com.heavy_rental.rest_api.dto;

import jakarta.validation.constraints.Pattern;

/**
 * {@code PATCH /api/rentalPlans/{id}} — sets or changes {@code siteAddress} on a plan created
 * without one (see {@code openspec/changes/pricing-postal-distance/} "Follow-on:
 * PATCH /api/rentalPlans/{id}"). Same validation as {@link RentalPlanCreateRequest} post-relaxation:
 * {@code @Pattern} alone already treats {@code null}/omitted as valid, non-null must still end in
 * a 6-digit postal code.
 */
public record RentalPlanUpdateRequest(
        @Pattern(
                regexp = "^.*\\d{6}$",
                message = "Site address must end with a 6-digit postal code, e.g. \"20 Jurong Port Road, 619094\"")
        String siteAddress) {

    public RentalPlanUpdateRequest {
        siteAddress = siteAddress == null ? null : siteAddress.strip();
    }
}
