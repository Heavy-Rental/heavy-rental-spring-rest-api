package com.heavy_rental.rest_api.dto;

/** Portal body for Call 3 knowledge-query ({@code POST .../knowledge-query}). */
public record ProjectKnowledgeQueryPortalRequest(String query, Integer topK) {
}
