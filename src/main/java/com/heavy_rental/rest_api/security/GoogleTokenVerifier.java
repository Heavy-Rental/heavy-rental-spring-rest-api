package com.heavy_rental.rest_api.security;

import java.util.Collections;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;

/**
 * Verifies signature, issuer, audience, and expiry of a Google-issued ID token
 * (from Android Credential Manager / Sign in with Google) against Google's public keys.
 */
@Service
public class GoogleTokenVerifier {

	private final GoogleIdTokenVerifier verifier;

	public GoogleTokenVerifier(@Value("${app.google.web-client-id}") String webClientId) {
		this.verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
				.setAudience(Collections.singletonList(webClientId))
				.build();
	}

	public GoogleIdToken.Payload verify(String idTokenString) {
		if (idTokenString == null || idTokenString.isBlank()) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Google ID token is required");
		}
		try {
			GoogleIdToken idToken = verifier.verify(idTokenString);
			if (idToken == null) {
				throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Google ID token");
			}
			return idToken.getPayload();
		} catch (ResponseStatusException e) {
			throw e;
		} catch (Exception e) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Google ID token");
		}
	}
}