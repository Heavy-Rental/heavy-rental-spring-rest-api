package com.heavy_rental.rest_api.dto;

public record UserResponse(
        Long id,
        String name,
        String email,
        String role) {

}
