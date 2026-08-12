package com.heavy_rental.rest_api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import com.heavy_rental.rest_api.client.haystack.HaystackException;
import com.heavy_rental.rest_api.client.haystack.HaystackRecommenderClient;
import com.heavy_rental.rest_api.client.haystack.dto.GetAssetRecommendationsRequest;
import com.heavy_rental.rest_api.client.haystack.dto.GetAssetRecommendationsResponse;
import com.heavy_rental.rest_api.client.haystack.dto.IngestFromProjectSpecResponse;
import com.heavy_rental.rest_api.client.haystack.dto.ProjectKnowledgeQueryRequest;
import com.heavy_rental.rest_api.client.haystack.dto.ProjectKnowledgeQueryResponse;
import com.heavy_rental.rest_api.client.haystack.dto.RecommendEquipmentDto;
import com.heavy_rental.rest_api.client.haystack.dto.RecommendItemDto;
import com.heavy_rental.rest_api.dto.ProjectKnowledgeQueryPortalRequest;
import com.heavy_rental.rest_api.dto.ProjectSpecSubmitCommand;
import com.heavy_rental.rest_api.dto.SubmitProjectSpecRequest;
import com.heavy_rental.rest_api.dto.SubmitProjectSpecResponse;
import com.heavy_rental.rest_api.entity.AIRecommendation;
import com.heavy_rental.rest_api.entity.User;
import com.heavy_rental.rest_api.repository.AIRecommendationRepository;

/**
 * BDD: FR-S2B-005/007 — dual-hop quote, multipart path, no re-ingest, Call 3 only for knowledge-query.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecommenderSagaService — dual-hop rules")
class RecommenderSagaServiceTest {

	@Mock
	private HaystackRecommenderClient haystackClient;
	@Mock
	private AIRecommendationRepository recommendationRepository;
	@Mock
	private CurrentUserService currentUserService;
	@Mock
	private Jwt jwt;

	private RecommenderSagaService saga;
	private User user;

	@BeforeEach
	void setUp() {
		saga = new RecommenderSagaService(haystackClient, recommendationRepository, currentUserService);
		user = new User();
		user.setId(7L);
		user.setName("Alex");
		user.setEmail("alex@example.com");
	}

	@DisplayName("Scenario: Portal dual-hop returns Call 2 quote and does not call Call 3")
	@Test
	void submitProjectSpec_runsIngestThenRecommend_returnsQuote() {
		when(currentUserService.getUser(jwt)).thenReturn(user);
		when(haystackClient.ingest(any(), anyString(), anyString())).thenReturn(
				new IngestFromProjectSpecResponse(
						"ing_abc", "7", "summary text",
						"2026-09-01", "2026-09-12",
						List.of(), null, List.of()));
		when(recommendationRepository.save(any())).thenAnswer(inv -> {
			AIRecommendation r = inv.getArgument(0);
			if (r.getId() == null) {
				r.setId(99L);
			}
			return r;
		});
		when(haystackClient.recommend(any(), eq("corr-1"))).thenReturn(sampleQuote(
				"QUO-9", "summary text", "ing_abc", "7"));

		SubmitProjectSpecResponse resp = saga.submitProjectSpec(
				jwt,
				new SubmitProjectSpecRequest("Need excavator", null, null, null, null, null),
				"corr-1");

		assertEquals(99L, resp.recommendationId());
		assertEquals("ing_abc", resp.ingestId());
		assertEquals("corr-1", resp.correlationId());
		assertEquals("QUO-9", resp.quoteRef());
		assertEquals(1, resp.items().size());
		assertEquals("asset-1", resp.items().get(0).equipmentId());

		verify(haystackClient, times(1)).ingest(any(), anyString(), anyString());
		ArgumentCaptor<GetAssetRecommendationsRequest> recCap =
				ArgumentCaptor.forClass(GetAssetRecommendationsRequest.class);
		verify(haystackClient, times(1)).recommend(recCap.capture(), eq("corr-1"));
		assertEquals("ing_abc", recCap.getValue().ingestId());
		assertEquals("7", recCap.getValue().userId());
		// default focus query uses Call 1 summary when portal query omitted
		assertEquals("summary text", recCap.getValue().query());
		verify(haystackClient, never()).queryProjectKnowledge(any(), anyString());

		ArgumentCaptor<AIRecommendation> cap = ArgumentCaptor.forClass(AIRecommendation.class);
		verify(recommendationRepository, times(2)).save(cap.capture());
		assertEquals("ing_abc", cap.getAllValues().get(0).getIngestId());
		assertNotNullKey(cap.getAllValues().get(0).getIdempotencyKey());
	}

	@DisplayName("Scenario: Portal query overrides Call 1 summary for Call 2 focus")
	@Test
	void submitProjectSpec_portalQueryOverridesSummary() {
		when(currentUserService.getUser(jwt)).thenReturn(user);
		when(haystackClient.ingest(any(), anyString(), anyString())).thenReturn(
				new IngestFromProjectSpecResponse(
						"ing_x", "7", "summary", null, null, List.of(), null, List.of()));
		when(recommendationRepository.save(any())).thenAnswer(inv -> {
			AIRecommendation r = inv.getArgument(0);
			if (r.getId() == null) {
				r.setId(1L);
			}
			return r;
		});
		when(haystackClient.recommend(any(), anyString())).thenReturn(sampleQuote(
				"QUO-1", "custom Q", "ing_x", "7"));

		saga.submitProjectSpec(
				jwt,
				new SubmitProjectSpecRequest("text", null, null, null, "custom Q", 5),
				"c");

		ArgumentCaptor<GetAssetRecommendationsRequest> recCap =
				ArgumentCaptor.forClass(GetAssetRecommendationsRequest.class);
		verify(haystackClient).recommend(recCap.capture(), anyString());
		assertEquals("custom Q", recCap.getValue().query());
		assertEquals(5, recCap.getValue().topK());
	}

	@DisplayName("Scenario: Multipart submit uses ingestMultipart then recommend")
	@Test
	void submitProjectSpec_multipart_callsIngestMultipartThenRecommend() {
		when(currentUserService.getUser(jwt)).thenReturn(user);
		when(haystackClient.ingestMultipart(any(), anyString(), anyString())).thenReturn(
				new IngestFromProjectSpecResponse(
						"ing_mp", "7", "from file", null, null, List.of(), null, List.of()));
		when(recommendationRepository.save(any())).thenAnswer(inv -> {
			AIRecommendation r = inv.getArgument(0);
			if (r.getId() == null) {
				r.setId(3L);
			}
			return r;
		});
		when(haystackClient.recommend(any(), anyString())).thenReturn(sampleQuote(
				"QUO-MP", "from file", "ing_mp", "7"));

		SubmitProjectSpecResponse resp = saga.submitProjectSpec(
				jwt,
				new ProjectSpecSubmitCommand(
						null, null, null, null, null, null,
						"file bytes".getBytes(), "spec.txt", "text/plain"),
				"corr-mp");

		assertEquals("QUO-MP", resp.quoteRef());
		assertEquals("ing_mp", resp.ingestId());
		verify(haystackClient, times(1)).ingestMultipart(any(), anyString(), eq("corr-mp"));
		verify(haystackClient, never()).ingest(any(), anyString(), anyString());
		verify(haystackClient, times(1)).recommend(any(), eq("corr-mp"));
	}

	@DisplayName("Scenario: Call 2 500 does not re-ingest and session was persisted")
	@Test
	void submitProjectSpec_onRecommendFailure_doesNotReIngest_butPersistsSession() {
		when(currentUserService.getUser(jwt)).thenReturn(user);
		when(haystackClient.ingest(any(), anyString(), anyString())).thenReturn(
				new IngestFromProjectSpecResponse(
						"ing_1", "7", "summary", null, null, List.of(), null, List.of()));
		when(recommendationRepository.save(any())).thenAnswer(inv -> {
			AIRecommendation r = inv.getArgument(0);
			r.setId(5L);
			return r;
		});
		when(haystackClient.recommend(any(), anyString()))
				.thenThrow(new HaystackException(500, "internal_error", "recommend down",
						HaystackException.Kind.UPSTREAM));

		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> saga.submitProjectSpec(
						jwt,
						new SubmitProjectSpecRequest("Need excavator", null, null, null, null, null),
						"corr-s"));

		assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
		verify(haystackClient, times(1)).ingest(any(), anyString(), anyString());
		verify(haystackClient, times(1)).recommend(any(), anyString());
		verify(haystackClient, never()).queryProjectKnowledge(any(), anyString());
		verify(recommendationRepository, times(1)).save(any());
	}

	@DisplayName("Scenario: Call 3 failure does not re-ingest")
	@Test
	void queryKnowledge_onQaFailure_doesNotReIngest() {
		AIRecommendation session = new AIRecommendation();
		session.setId(5L);
		session.setUser(user);
		session.setIngestId("ing_1");
		session.setHaystackUserId("7");
		session.setCorrelationId("corr-s");

		when(recommendationRepository.findById(5L)).thenReturn(Optional.of(session));
		when(haystackClient.queryProjectKnowledge(any(ProjectKnowledgeQueryRequest.class), eq("corr-s")))
				.thenThrow(new HaystackException(500, "internal_error", "qa down", HaystackException.Kind.UPSTREAM));

		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> saga.queryKnowledge(jwt, 5L, new ProjectKnowledgeQueryPortalRequest("What height?", null)));

		assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
		verify(haystackClient, never()).ingest(any(), anyString(), anyString());
		verify(haystackClient, never()).recommend(any(), anyString());
		verify(haystackClient, times(1)).queryProjectKnowledge(any(), anyString());
	}

	@DisplayName("Scenario: Knowledge-query uses stored ingest_id and Call 3 only")
	@Test
	void queryKnowledge_usesStoredIngestId_call3Only() {
		AIRecommendation session = new AIRecommendation();
		session.setId(5L);
		session.setUser(user);
		session.setIngestId("ing_stored");
		session.setHaystackUserId("7");
		session.setCorrelationId("corr-s");

		when(recommendationRepository.findById(5L)).thenReturn(Optional.of(session));
		when(haystackClient.queryProjectKnowledge(any(), anyString()))
				.thenReturn(new ProjectKnowledgeQueryResponse(
						"answer md", List.of("kg"), null, null, null));

		var resp = saga.queryKnowledge(jwt, 5L, new ProjectKnowledgeQueryPortalRequest("q", 3));
		assertEquals("answer md", resp.answer());

		ArgumentCaptor<ProjectKnowledgeQueryRequest> cap = ArgumentCaptor.forClass(ProjectKnowledgeQueryRequest.class);
		verify(haystackClient).queryProjectKnowledge(cap.capture(), eq("corr-s"));
		assertEquals("ing_stored", cap.getValue().ingestId());
		assertEquals("7", cap.getValue().userId());
		assertEquals("q", cap.getValue().query());
		assertEquals(3, cap.getValue().topK());
		verify(haystackClient, never()).recommend(any(), anyString());
	}

	@DisplayName("Scenario: Call 2 query priority is portal then summary then default")
	@Test
	void resolveCall2Query_priority() {
		assertEquals("portal", RecommenderSagaService.resolveCall2Query("portal", "summary"));
		assertEquals("summary", RecommenderSagaService.resolveCall2Query(null, "summary"));
		assertEquals(RecommenderSagaService.DEFAULT_ASSET_QUERY,
				RecommenderSagaService.resolveCall2Query("  ", "  "));
	}

	private static GetAssetRecommendationsResponse sampleQuote(
			String quoteRef, String query, String ingestId, String userId) {
		return new GetAssetRecommendationsResponse(
				userId,
				ingestId,
				query,
				quoteRef,
				new BigDecimal("0.9"),
				10,
				new BigDecimal("1500.00"),
				"Indoor elevated access",
				"Scissor lift fits",
				List.of(new RecommendItemDto(
						1,
						new RecommendEquipmentDto("asset-1", "Genie GS-1930", "Scissor Lift"),
						new BigDecimal("150.00"),
						new BigDecimal("1500.00"),
						null)),
				List.of());
	}

	private static void assertNotNullKey(String key) {
		assertTrue(key != null && !key.isBlank());
	}
}
