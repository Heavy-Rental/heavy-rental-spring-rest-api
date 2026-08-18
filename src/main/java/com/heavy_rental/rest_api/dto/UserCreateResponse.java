package com.heavy_rental.rest_api.dto;

public record UserCreateResponse(
        Long id,
        String name,
        String email,
        String role,
        String temporaryPassword) {

}
