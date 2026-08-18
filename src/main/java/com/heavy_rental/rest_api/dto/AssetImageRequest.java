package com.heavy_rental.rest_api.dto;

import jakarta.validation.constraints.NotBlank;

public record AssetImageRequest(@NotBlank String image) {
}
