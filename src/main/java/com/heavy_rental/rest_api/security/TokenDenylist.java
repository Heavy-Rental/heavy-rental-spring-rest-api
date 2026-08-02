package com.heavy_rental.rest_api.security;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * In-memory denylist of revoked JWT IDs ({@code jti}).
 * Entries expire when the original token would have expired.
 */
@Component
public class TokenDenylist {

	private final Map<String, Instant> deniedUntil = new ConcurrentHashMap<>();

	public void deny(String jti, Instant expiresAt) {
		if (jti == null || jti.isBlank()) {
			return;
		}
		Instant expiry = expiresAt != null ? expiresAt : Instant.now().plusSeconds(3600);
		deniedUntil.put(jti, expiry);
	}

	public boolean isDenied(String jti) {
		if (jti == null || jti.isBlank()) {
			return false;
		}
		Instant expiresAt = deniedUntil.get(jti);
		if (expiresAt == null) {
			return false;
		}
		if (Instant.now().isAfter(expiresAt)) {
			deniedUntil.remove(jti);
			return false;
		}
		return true;
	}

	@Scheduled(fixedDelayString = "PT15M")
	void purgeExpired() {
		Instant now = Instant.now();
		deniedUntil.entrySet().removeIf(entry -> now.isAfter(entry.getValue()));
	}
}
