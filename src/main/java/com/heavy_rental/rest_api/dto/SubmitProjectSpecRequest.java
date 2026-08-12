package com.heavy_rental.rest_api.dto;

import java.time.LocalDate;

/**
 * Portal JSON body for {@code POST /api/recommendations/project-spec}.
 * <p>
 * After Call 1 ingest, Spring runs Call 2 recommend with optional focus {@code query}.
 * Chatbot Q&amp;A is Call 3 via knowledge-query, not this request.
 *
 * @param projectText non-empty project specification text
 * @param startDate optional rental window start
 * @param endDate optional rental window end
 * @param userName optional display name
 * @param query optional Call 2 focus; else Call 1 summary then default text
 * @param topK optional Call 2 item cap
 */
public record SubmitProjectSpecRequest(
		String projectText,
		LocalDate startDate,
		LocalDate endDate,
		String userName,
		String query,
		Integer topK) {
}
