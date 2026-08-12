package com.heavy_rental.rest_api.dto;

import java.time.LocalDate;

/**
 * Unified portal project-spec command for JSON or multipart submit.
 * <p>
 * At least one of {@code projectText} or file bytes is required. Multipart form
 * field names on the portal are camelCase; haystack form fields are snake_case
 * (mapped in the client).
 */
public record ProjectSpecSubmitCommand(
		String projectText,
		LocalDate startDate,
		LocalDate endDate,
		String userName,
		String query,
		Integer topK,
		byte[] fileBytes,
		String fileName,
		String fileContentType) {

	/** Build from JSON request (no file). */
	public static ProjectSpecSubmitCommand fromJson(SubmitProjectSpecRequest request) {
		if (request == null) {
			return new ProjectSpecSubmitCommand(null, null, null, null, null, null, null, null, null);
		}
		return new ProjectSpecSubmitCommand(
				request.projectText(),
				request.startDate(),
				request.endDate(),
				request.userName(),
				request.query(),
				request.topK(),
				null,
				null,
				null);
	}

	public boolean hasFile() {
		return fileBytes != null && fileBytes.length > 0;
	}

	public boolean hasProjectText() {
		return projectText != null && !projectText.isBlank();
	}
}
