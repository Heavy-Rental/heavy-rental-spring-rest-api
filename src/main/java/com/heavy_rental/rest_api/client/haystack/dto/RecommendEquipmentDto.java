package com.heavy_rental.rest_api.client.haystack.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Nested equipment reference on a Call 2 quote item. {@code id} is catalog asset id when known. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RecommendEquipmentDto(
		String id,
		String name,
		String category) {
}
