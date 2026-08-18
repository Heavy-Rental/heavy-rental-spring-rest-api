package com.heavy_rental.rest_api.dto;

import java.util.List;

/** Portal display of Call 1 needs summary (not fleet recommendations). */
public record NeedSummaryResponse(
		String needId,
		String description,
		List<String> equipmentHints,
		Integer quantity) {
}
