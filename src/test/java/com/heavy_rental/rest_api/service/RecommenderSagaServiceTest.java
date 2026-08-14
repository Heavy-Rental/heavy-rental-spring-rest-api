package com.heavy_rental.rest_api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.heavy_rental.rest_api.entity.Asset;
import com.heavy_rental.rest_api.entity.AssetImage;
import com.heavy_rental.rest_api.entity.User;
import com.heavy_rental.rest_api.repository.AIRecommendationRepository;
import com.heavy_rental.rest_api.repository.AssetImageRepository;

/**
 * BDD scenarios for recommender saga: FR-S2B-005/007 (dual-hop, no re-ingest, Call 3)
 * and FR-S2B-010 (nested portal quote items).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecommenderSagaService — dual-hop + FR-S2B-010 nested items")
class RecommenderSagaServiceTest {

	@Mock
	private HaystackRecommenderClient haystackClient;
	@Mock
	private AIRecommendationRepository recommendationRepository;
	@Mock
	private CurrentUserService currentUserService;
	@Mock
	private AssetImageRepository assetImageRepository;
	@Mock
	private Jwt jwt;

	private RecommenderSagaService saga;
	private User user;

	@BeforeEach
	void setUp() {
		saga = new RecommenderSagaService(
				haystackClient, recommendationRepository, currentUserService, assetImageRepository);
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
		assertEquals(1, resp.items().get(0).rankOrder());
		assertEquals(new BigDecimal("0.95"), resp.items().get(0).matchScore());
		assertEquals("Fits indoor height requirement", resp.items().get(0).reason());
		assertEquals(Integer.valueOf(1), resp.items().get(0).quantity());
		assertEquals("asset-1", resp.items().get(0).equipment().id());
		assertEquals("Genie GS-1930", resp.items().get(0).equipment().name());
		assertEquals(new BigDecimal("150.00"), resp.items().get(0).equipment().baseDailyRate());
		assertEquals(new BigDecimal("1500.00"), resp.items().get(0).lineTotal());

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

	@DisplayName("Scenario: Submit response exposes nested equipment (FR-S2B-010)")
	@Test
	void submitProjectSpec_exposesNestedEquipment_withOptionalFields() {
		// GIVEN Call 2 returns nested equipment + reason / quantity / matchScore
		stubIngestAndSave(99L);
		when(haystackClient.recommend(any(), eq("corr-nested"))).thenReturn(quoteWithItems(
				List.of(new RecommendItemDto(
						1,
						new BigDecimal("0.91"),
						"135ft reach covers elevation",
						new BigDecimal("12180"),
						1,
						new RecommendEquipmentDto(
								1,
								"JLG 1350SJP Telescopic Boom",
								"Boom Lift",
								new BigDecimal("580"),
								new BigDecimal("2600"),
								1,
								new BigDecimal("41.15"),
								2023,
								"Jurong Port",
								true,
								"photo-abc",
								"Telescopic boom",
								List.of("135ft Reach", "4WD")),
						null))));

		// WHEN the portal project-spec response is built
		SubmitProjectSpecResponse resp = saga.submitProjectSpec(
				jwt,
				new SubmitProjectSpecRequest("Need boom lift", null, null, null, null, null),
				"corr-nested");

		// THEN nested equipment and optional item fields are present; no fabrication of empties into rates
		var item = resp.items().get(0);
		assertEquals(1, item.rankOrder());
		assertEquals(new BigDecimal("0.91"), item.matchScore());
		assertEquals("135ft reach covers elevation", item.reason());
		assertEquals(new BigDecimal("12180"), item.lineTotal());
		assertEquals(Integer.valueOf(1), item.quantity());
		assertNotNull(item.equipment());
		assertEquals(1L, item.equipment().id());
		assertEquals("JLG 1350SJP Telescopic Boom", item.equipment().name());
		assertEquals("Boom Lift", item.equipment().category());
		assertEquals(new BigDecimal("580"), item.equipment().baseDailyRate());
		assertEquals(new BigDecimal("2600"), item.equipment().weekly());
		assertEquals(Integer.valueOf(1), item.equipment().capacity());
		assertEquals(new BigDecimal("41.15"), item.equipment().platformHeight());
		assertEquals(Integer.valueOf(2023), item.equipment().purchaseYear());
		assertEquals("Jurong Port", item.equipment().location());
		assertEquals(Boolean.TRUE, item.equipment().available());
		assertEquals("photo-abc", item.equipment().img());
		assertEquals("Telescopic boom", item.equipment().desc());
		assertEquals(List.of("135ft Reach", "4WD"), item.equipment().tags());
	}

	@DisplayName("Scenario: Item-level baseDailyRate falls back onto equipment (FR-S2B-010)")
	@Test
	void submitProjectSpec_itemBaseDailyRateFallsBackOntoEquipment() {
		// GIVEN haystack places baseDailyRate on the item and omits it on equipment
		stubIngestAndSave(10L);
		when(haystackClient.recommend(any(), anyString())).thenReturn(quoteWithItems(
				List.of(new RecommendItemDto(
						1,
						null,
						null,
						new BigDecimal("1500.00"),
						null,
						new RecommendEquipmentDto(
								"asset-1",
								"Genie GS-1930",
								"Scissor Lift",
								null,
								null,
								null,
								null,
								null,
								null,
								null,
								null,
								null,
								null),
						new BigDecimal("150.00")))));

		// WHEN mapped for the portal
		SubmitProjectSpecResponse resp = saga.submitProjectSpec(
				jwt,
				new SubmitProjectSpecRequest("Need scissors", null, null, null, null, null),
				"corr-fb");

		// THEN equipment.baseDailyRate receives the item rate; other rates stay null (not invented)
		var equip = resp.items().get(0).equipment();
		assertEquals(new BigDecimal("150.00"), equip.baseDailyRate());
		assertNull(equip.weekly());
		assertNull(equip.capacity());
		assertNull(equip.platformHeight());
		assertNull(equip.purchaseYear());
		assertNull(equip.location());
		assertNull(equip.available());
		assertEquals(List.of(), equip.tags());
	}

	@DisplayName("Scenario: Null equipment and omitted scores are not invented (FR-S2B-010)")
	@Test
	void submitProjectSpec_doesNotInventEquipmentOrScoresWhenOmitted() {
		// GIVEN Call 2 item with no equipment and no matchScore/reason
		stubIngestAndSave(11L);
		when(haystackClient.recommend(any(), anyString())).thenReturn(quoteWithItems(
				List.of(new RecommendItemDto(
						2,
						null,
						null,
						new BigDecimal("100.00"),
						null,
						null,
						null))));

		// WHEN portal response is built
		SubmitProjectSpecResponse resp = saga.submitProjectSpec(
				jwt,
				new SubmitProjectSpecRequest("Need something", null, null, null, null, null),
				"corr-null");

		// THEN nulls stay null — no synthetic equipment object or scores
		var item = resp.items().get(0);
		assertEquals(2, item.rankOrder());
		assertNull(item.matchScore());
		assertNull(item.reason());
		assertNull(item.quantity());
		assertNull(item.equipment());
		assertEquals(new BigDecimal("100.00"), item.lineTotal());
	}

	@DisplayName("Scenario: Numeric string equipment id normalizes to Long (FR-S2B-010)")
	@Test
	void submitProjectSpec_numericStringEquipmentIdBecomesLong() {
		// GIVEN haystack equipment.id is the string "1"
		stubIngestAndSave(12L);
		when(haystackClient.recommend(any(), anyString())).thenReturn(quoteWithItems(
				List.of(new RecommendItemDto(
						1,
						null,
						null,
						null,
						1,
						new RecommendEquipmentDto(
								"1",
								"CAT 320",
								"Excavator",
								null, null, null, null, null, null, null, null, null, null),
						null))));

		// WHEN mapped
		SubmitProjectSpecResponse resp = saga.submitProjectSpec(
				jwt,
				new SubmitProjectSpecRequest("Need excavator", null, null, null, null, null),
				"corr-id");

		// THEN portal equipment.id is Long 1 (catalog-friendly JSON number)
		assertEquals(1L, resp.items().get(0).equipment().id());
	}

	@DisplayName("Scenario: Catalog image is loaded onto equipment.img by numeric id")
	@Test
	void submitProjectSpec_setsImgFromCatalogAssetImage() {
		stubIngestAndSave(13L);
		Asset asset = new Asset();
		asset.setId(1L);
		AssetImage image = new AssetImage();
		image.setAsset(asset);
		image.setImage("abc123");
		when(assetImageRepository.findByAssetIdIn(any())).thenReturn(List.of(image));
		when(haystackClient.recommend(any(), anyString())).thenReturn(quoteWithItems(
				List.of(new RecommendItemDto(
						1,
						null,
						null,
						null,
						1,
						new RecommendEquipmentDto(
								"1",
								"CAT 320",
								"Excavator",
								null, null, null, null, null, null, null, "photo-abc", null, null),
						null))));

		SubmitProjectSpecResponse resp = saga.submitProjectSpec(
				jwt,
				new SubmitProjectSpecRequest("Need excavator", null, null, null, null, null),
				"corr-img");

		assertEquals("data:image/jpeg;base64,abc123", resp.items().get(0).equipment().img());
	}

	private void stubIngestAndSave(long recommendationId) {
		when(currentUserService.getUser(jwt)).thenReturn(user);
		when(haystackClient.ingest(any(), anyString(), anyString())).thenReturn(
				new IngestFromProjectSpecResponse(
						"ing_fr", "7", "summary", null, null, List.of(), null, List.of()));
		when(recommendationRepository.save(any())).thenAnswer(inv -> {
			AIRecommendation r = inv.getArgument(0);
			if (r.getId() == null) {
				r.setId(recommendationId);
			}
			return r;
		});
	}

	private static GetAssetRecommendationsResponse sampleQuote(
			String quoteRef, String query, String ingestId, String userId) {
		return quoteWithItems(
				userId,
				ingestId,
				query,
				quoteRef,
				List.of(new RecommendItemDto(
						1,
						new BigDecimal("0.95"),
						"Fits indoor height requirement",
						new BigDecimal("1500.00"),
						1,
						new RecommendEquipmentDto(
								"asset-1",
								"Genie GS-1930",
								"Scissor Lift",
								null,
								null,
								null,
								null,
								null,
								null,
								null,
								null,
								null,
								null),
						new BigDecimal("150.00"))));
	}

	private static GetAssetRecommendationsResponse quoteWithItems(List<RecommendItemDto> items) {
		return quoteWithItems("7", "ing_fr", "summary", "QUO-FR", items);
	}

	private static GetAssetRecommendationsResponse quoteWithItems(
			String userId, String ingestId, String query, String quoteRef, List<RecommendItemDto> items) {
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
				items,
				List.of());
	}

	private static void assertNotNullKey(String key) {
		assertTrue(key != null && !key.isBlank());
	}
}
