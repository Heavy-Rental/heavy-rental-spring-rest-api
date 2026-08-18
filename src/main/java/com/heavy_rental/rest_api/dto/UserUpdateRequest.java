package com.heavy_rental.rest_api.dto;

public record UserUpdateRequest(
        String name,
        String email,
        String role) {

}
