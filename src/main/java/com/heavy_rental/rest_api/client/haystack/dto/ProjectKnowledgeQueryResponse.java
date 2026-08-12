package com.heavy_rental.rest_api.client.haystack.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Call 3 chatbot Q&amp;A response. Portal knowledge-query maps {@code answer} / {@code sources_used}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectKnowledgeQueryResponse(
		String answer,
		@JsonProperty("sources_used") List<String> sourcesUsed,
		@JsonProperty("research_hits") Object researchHits,
		@JsonProperty("graph_hits") Object graphHits,
		@JsonProperty("tool_traces") Object toolTraces) {
}
