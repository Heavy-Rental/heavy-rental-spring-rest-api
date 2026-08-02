package com.heavy_rental.rest_api.security;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.heavy_rental.rest_api.config.JwtProperties;

@Service
public class JwtService {

	private final JwtEncoder jwtEncoder;
	private final JwtProperties jwtProperties;

	public JwtService(JwtEncoder jwtEncoder, JwtProperties jwtProperties) {
		this.jwtEncoder = jwtEncoder;
		this.jwtProperties = jwtProperties;
	}

	/**
	 * Issue a JWT whose subject is a random UUID and which records {@code generatedAt}.
	 *
	 * @param subject     typically a random UUID string
	 * @param roles       application roles (e.g. {@code ROLE_USER})
	 * @param generatedAt date/time used when the token was minted
	 */
	public Jwt generateToken(String subject, List<String> roles, Instant generatedAt) {
		Instant issuedAt = generatedAt != null ? generatedAt : Instant.now();
		Instant expiresAt = issuedAt.plusSeconds(jwtProperties.expirationMinutes() * 60);
		List<String> safeRoles = roles != null ? List.copyOf(roles) : List.of();

		JwtClaimsSet claims = JwtClaimsSet.builder()
				.id(UUID.randomUUID().toString())
				.issuer(jwtProperties.issuer())
				.issuedAt(issuedAt)
				.expiresAt(expiresAt)
				.subject(subject)
				.claim("roles", safeRoles)
				.claim("generatedAt", issuedAt.toString())
				.build();

		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		return jwtEncoder.encode(JwtEncoderParameters.from(header, claims));
	}

	public long getExpiresInSeconds() {
		return jwtProperties.expirationMinutes() * 60;
	}

	@SuppressWarnings("unchecked")
	public static List<String> rolesFrom(Jwt jwt) {
		Object roles = jwt.getClaim("roles");
		if (roles instanceof Collection<?> collection) {
			return collection.stream().map(String::valueOf).toList();
		}
		return List.of();
	}
}
