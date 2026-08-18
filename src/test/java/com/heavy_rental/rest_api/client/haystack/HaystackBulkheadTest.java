package com.heavy_rental.rest_api.client.haystack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;

/**
 * BDD: FR-S2B-002 — bulkhead rejects excess concurrent calls (plan §7 #3).
 * Unit-level (no WireMock): max concurrent = 1 rejects a second concurrent call.
 */
@DisplayName("Haystack bulkhead")
class HaystackBulkheadTest {

	@Test
	@DisplayName("Scenario: Bulkhead with max concurrent 1 rejects a second concurrent call")
	void bulkheadRejectsWhenFull() throws Exception {
		Bulkhead bulkhead = Bulkhead.of("bh-1", BulkheadConfig.custom()
				.maxConcurrentCalls(1)
				.maxWaitDuration(Duration.ZERO)
				.build());

		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);

		Future<?> holder = pool.submit(() -> Bulkhead.decorateRunnable(bulkhead, () -> {
			entered.countDown();
			try {
				release.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}).run());

		assertTrue(entered.await(2, TimeUnit.SECONDS));

		AtomicReference<Exception> secondError = new AtomicReference<>();
		Future<?> second = pool.submit(() -> {
			try {
				Bulkhead.decorateRunnable(bulkhead, () -> {
				}).run();
			} catch (Exception e) {
				secondError.set(e);
			}
		});
		second.get(2, TimeUnit.SECONDS);

		assertThrows(BulkheadFullException.class, () -> {
			Exception e = secondError.get();
			if (e != null) {
				throw e;
			}
		});
		// Prefer direct assert if exception captured
		assertEquals(BulkheadFullException.class, secondError.get().getClass());

		release.countDown();
		holder.get(2, TimeUnit.SECONDS);
		pool.shutdownNow();
	}

	private static void assertTrue(boolean cond) {
		org.junit.jupiter.api.Assertions.assertTrue(cond);
	}
}
