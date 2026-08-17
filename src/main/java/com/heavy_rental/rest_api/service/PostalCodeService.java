package com.heavy_rental.rest_api.service;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.heavy_rental.rest_api.client.onemap.Coordinates;
import com.heavy_rental.rest_api.client.onemap.OneMapClient;
import com.heavy_rental.rest_api.client.onemap.OneMapException;
import com.heavy_rental.rest_api.dto.PostalCodeValidationResponse;
import com.heavy_rental.rest_api.util.PostalCodeUtil;

/**
 * Backs {@code GET /api/postalCodes/{postalCode}} — real-time postal code validation for the web
 * portal's site-address form (see {@code openspec/changes/pricing-postal-distance/}), reusing the
 * same {@link OneMapClient} (and its cache) that {@link DistanceService} uses at quote time.
 * <p>
 * Unlike {@link DistanceService}, a OneMap outage here is surfaced to the caller (as a 503) rather
 * than silently substituted — but the product decision (see proposal.md) is that the frontend
 * must not hard-block the user on that response, only on a genuine {@code INVALID}.
 */
@Service
public class PostalCodeService {

    private static final Logger log = LoggerFactory.getLogger(PostalCodeService.class);

    private final OneMapClient oneMapClient;

    public PostalCodeService(OneMapClient oneMapClient) {
        this.oneMapClient = oneMapClient;
    }

    public ResponseEntity<PostalCodeValidationResponse> validate(String rawPostalCode) {
        String postalCode = rawPostalCode == null ? "" : rawPostalCode.strip();
        if (!PostalCodeUtil.isWellFormed(postalCode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Postal code must be exactly 6 digits");
        }

        try {
            Optional<Coordinates> result = oneMapClient.geocode(postalCode);
            if (result.isPresent()) {
                return ResponseEntity.ok(PostalCodeValidationResponse.valid(postalCode, result.get().address()));
            }
            return ResponseEntity.ok(PostalCodeValidationResponse.invalid(postalCode));
        } catch (OneMapException ex) {
            // postalCode is already regex-validated above (no CR/LF possible), but ex.getMessage()
            // can carry OneMap's raw response body verbatim (see OneMapClient/OneMapAuthService
            // mapException) — an untrusted external value. Strip line breaks from both before
            // logging so neither can forge extra log lines (CWE-117 log injection).
            log.warn("Postal code validation unavailable for {} ({}: {})",
                    sanitizeForLog(postalCode), ex.getErrorCode(), sanitizeForLog(ex.getMessage()));
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(PostalCodeValidationResponse.unavailable(postalCode));
        }
    }

    private static String sanitizeForLog(String value) {
        return value == null ? null : value.replaceAll("[\r\n]", "_");
    }
}
