package com.heavy_rental.rest_api.service;

/**
 * A 409 on a RentalPlan/Booking business rule (e.g. checkout attempted on a plan that isn't
 * QUOTED, or a quote that's gone stale) that needs its own stable {@code code} — unlike a plain
 * {@link org.springframework.web.server.ResponseStatusException}, which
 * {@link com.heavy_rental.rest_api.config.RestExceptionHandler} maps to one generic
 * {@code "conflict"} code per HTTP status, giving callers no way to tell these conditions apart
 * without parsing the message text.
 */
public class RentalPlanConflictException extends RuntimeException {

    private final String code;

    public RentalPlanConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
