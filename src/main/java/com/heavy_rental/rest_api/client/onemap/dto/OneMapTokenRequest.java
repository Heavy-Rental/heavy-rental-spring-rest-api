package com.heavy_rental.rest_api.client.onemap.dto;

/** Request body for {@code POST /api/auth/post/getToken}. */
public record OneMapTokenRequest(String email, String password) {
}
