package com.heavy_rental.rest_api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response for {@code GET /api/postalCodes/{postalCode}} (see
 * {@code openspec/changes/pricing-postal-distance/}).
 * <p>
 * {@code status} is {@code VALID} (postal code resolved — {@code address} is populated),
 * {@code INVALID} (well-formed but OneMap has no match — {@code message} explains why), or
 * {@code UNAVAILABLE} (OneMap itself is down/timing out — {@code message} explains why). VALID
 * and INVALID both return HTTP 200, distinguished only by this field; UNAVAILABLE returns 503, so
 * the frontend's normal fetch-error handling can tell "OneMap is down" apart from "field is
 * genuinely invalid" using the HTTP status alone.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PostalCodeValidationResponse(String status, String postalCode, String address, String message) {

    public static PostalCodeValidationResponse valid(String postalCode, String address) {
        return new PostalCodeValidationResponse("VALID", postalCode, address, null);
    }

    public static PostalCodeValidationResponse invalid(String postalCode) {
        return new PostalCodeValidationResponse(
                "INVALID", postalCode, null, "No address found for this postal code");
    }

    public static PostalCodeValidationResponse unavailable(String postalCode) {
        return new PostalCodeValidationResponse(
                "UNAVAILABLE", postalCode, null, "Postal code lookup is temporarily unavailable — you may continue");
    }
}
