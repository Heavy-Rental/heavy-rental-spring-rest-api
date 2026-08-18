package com.heavy_rental.rest_api.client.haystack;

/**
 * Call 1 multipart ingest payload for haystack {@code submitprojectspecification}.
 * <p>
 * Form field names on the wire are snake_case ({@code user_id}, {@code project_text}, {@code file}, …).
 * At least one of file bytes or non-blank {@code projectText} is required.
 */
public record IngestMultipartCommand(
		String userId,
		String userName,
		String projectText,
		String startDate,
		String endDate,
		byte[] fileBytes,
		String fileName,
		String fileContentType) {

	public boolean hasFile() {
		return fileBytes != null && fileBytes.length > 0;
	}

	public boolean hasProjectText() {
		return projectText != null && !projectText.isBlank();
	}
}
