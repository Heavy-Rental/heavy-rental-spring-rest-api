package com.heavy_rental.rest_api.client.onemap;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * BDD: {@link OneMapAuthService}'s token cache — fetched once and reused, refetched once past
 * the refresh buffer, and concurrent refreshers collapse into a single HTTP call. Uses an
 * injectable, settable {@link Clock} (package-private constructor) rather than real sleeps.
 */
@DisplayName("OneMapAuthService — token caching & refresh")
class OneMapAuthServiceTest {

	private static final String TOKEN_PATH = "/api/auth/post/getToken";

	private WireMockServer wireMock;
	private OneMapProperties properties;
	private MutableClock clock;

	@BeforeEach
	void setUp() {
		wireMock = new WireMockServer(wireMockConfig().dynamicPort());
		wireMock.start();

		properties = new OneMapProperties();
		properties.setBaseUrl(wireMock.baseUrl());
		properties.setEmail("test@example.com");
		properties.setPassword("secret");
		properties.getTimeouts().setConnect(Duration.ofSeconds(2));
		properties.getTimeouts().setRead(Duration.ofSeconds(2));
		properties.setTokenRefreshBuffer(Duration.ofHours(6));

		clock = new MutableClock(Instant.parse("2026-08-17T00:00:00Z"));
	}

	@AfterEach
	void tearDown() {
		wireMock.stop();
	}

	private void stubToken(String token, Instant expiresAt) {
		wireMock.stubFor(post(urlEqualTo(TOKEN_PATH))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody("{\"access_token\":\"" + token + "\",\"expiry_timestamp\":\""
								+ expiresAt.getEpochSecond() + "\"}")));
	}

	@Test
	@DisplayName("Scenario: token is fetched once and reused while still within the refresh buffer")
	void getValidToken_reusesCachedTokenWhileFresh() {
		stubToken("tok-1", clock.instant().plus(Duration.ofDays(3)));
		OneMapAuthService authService = new OneMapAuthService(properties, RestClient.builder(), clock);

		String first = authService.getValidToken();
		clock.advance(Duration.ofHours(1));
		String second = authService.getValidToken();

		assertEquals("tok-1", first);
		assertEquals("tok-1", second);
		wireMock.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
	}

	@Test
	@DisplayName("Scenario: token is refetched once \"now\" crosses the refresh buffer before expiry")
	void getValidToken_refetchesOnceWithinRefreshBufferOfExpiry() {
		stubToken("tok-1", clock.instant().plus(Duration.ofDays(3)));
		OneMapAuthService authService = new OneMapAuthService(properties, RestClient.builder(), clock);

		String first = authService.getValidToken();

		// 3 days minus 1 hour is inside the 6h refresh buffer before the token's real expiry.
		clock.advance(Duration.ofDays(3).minus(Duration.ofHours(1)));
		stubToken("tok-2", clock.instant().plus(Duration.ofDays(3)));
		String second = authService.getValidToken();

		assertEquals("tok-1", first);
		assertEquals("tok-2", second);
		assertNotEquals(first, second);
		wireMock.verify(2, postRequestedFor(urlEqualTo(TOKEN_PATH)));
	}

	@Test
	@DisplayName("Scenario: concurrent callers during a stale cache trigger only one token fetch")
	void getValidToken_concurrentCallersDuringRefresh_triggerSingleFetch() throws InterruptedException {
		wireMock.stubFor(post(urlEqualTo(TOKEN_PATH))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withFixedDelay(200)
						.withBody("{\"access_token\":\"tok-concurrent\",\"expiry_timestamp\":\""
								+ clock.instant().plus(Duration.ofDays(3)).getEpochSecond() + "\"}")));

		OneMapAuthService authService = new OneMapAuthService(properties, RestClient.builder(), clock);

		int threadCount = 8;
		ExecutorService pool = Executors.newFixedThreadPool(threadCount);
		CountDownLatch ready = new CountDownLatch(threadCount);
		CountDownLatch go = new CountDownLatch(1);
		AtomicReference<String> firstResult = new AtomicReference<>();
		try {
			for (int i = 0; i < threadCount; i++) {
				pool.submit(() -> {
					ready.countDown();
					try {
						go.await();
					} catch (InterruptedException ignored) {
						Thread.currentThread().interrupt();
					}
					firstResult.compareAndSet(null, authService.getValidToken());
				});
			}
			ready.await(2, TimeUnit.SECONDS);
			go.countDown();
			pool.shutdown();
			pool.awaitTermination(5, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}

		assertEquals("tok-concurrent", firstResult.get());
		wireMock.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
	}

	/** Settable {@link Clock} so refresh-buffer boundaries can be tested without real sleeps. */
	private static final class MutableClock extends Clock {
		private Instant now;

		MutableClock(Instant now) {
			this.now = now;
		}

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}
