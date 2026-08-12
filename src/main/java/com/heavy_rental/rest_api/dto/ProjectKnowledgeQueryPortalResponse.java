package com.heavy_rental.rest_api.dto;

import java.util.List;

/** Portal Call 3 response: chatbot answer only (not a recommend quote). */
public record ProjectKnowledgeQueryPortalResponse(String answer, List<String> sourcesUsed) {
}
