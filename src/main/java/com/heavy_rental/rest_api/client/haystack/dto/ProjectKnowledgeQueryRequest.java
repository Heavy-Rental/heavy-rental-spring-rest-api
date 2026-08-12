package com.heavy_rental.rest_api.client.haystack.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Call 3 chatbot Q&amp;A request for {@code POST .../project-knowledge/query}.
 * {@code query} is required on the wire.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProjectKnowledgeQueryRequest(
		@JsonProperty("user_id") String userId,
		@JsonProperty("ingest_id") String ingestId,
		String query,
		@JsonProperty("top_k") Integer topK,
		@JsonProperty("kg_artifact_path") String kgArtifactPath) {
}
