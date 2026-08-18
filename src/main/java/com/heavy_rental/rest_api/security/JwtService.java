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

	public static final String TOKEN_TYPE_INTERIM = "interim";
	public static final String TOKEN_TYPE_ACCESS = "access";
	public static final String CLAIM_TOKEN_TYPE = "tokenType";
	public static final String CLAIM_GENERATED_AT = "generatedAt";

	private final JwtEncoder jwtEncoder;
	private final JwtProperties jwtProperties;

	public JwtService(JwtEncoder jwtEncoder, JwtProperties jwtProperties) {
		this.jwtEncoder = jwtEncoder;
		this.jwtProperties = jwtProperties;
	}

	/**
	 * Issue a JWT with the given subject, roles, mint time, and token tier.
	 *
	 * @param subject     interim: random UUID; access: username
	 * @param roles       authorities stored in claim {@code roles}
	 * @param generatedAt mint time ({@code iat} and optional {@code generatedAt})
	 * @param tokenType   {@link #TOKEN_TYPE_INTERIM} or {@link #TOKEN_TYPE_ACCESS}
	 */
	public Jwt generateToken(String subject, List<String> roles, Instant generatedAt, String tokenType) {
		Instant issuedAt = generatedAt != null ? generatedAt : Instant.now();
		Instant expiresAt = issuedAt.plusSeconds(jwtProperties.expirationMinutes() * 60);
		List<String> safeRoles = roles != null ? List.copyOf(roles) : List.of();
		String type = tokenType != null ? tokenType : TOKEN_TYPE_ACCESS;

		JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
				.id(UUID.randomUUID().toString())
				.issuer(jwtProperties.issuer())
				.issuedAt(issuedAt)
				.expiresAt(expiresAt)
				.subject(subject)
				.claim("roles", safeRoles)
				.claim(CLAIM_TOKEN_TYPE, type);

		if (TOKEN_TYPE_INTERIM.equals(type)) {
			claims.claim(CLAIM_GENERATED_AT, issuedAt.toString());
		}

		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		return jwtEncoder.encode(JwtEncoderParameters.from(header, claims.build()));
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

	public static String tokenTypeFrom(Jwt jwt) {
		if (jwt == null) {
			return null;
		}
		Object type = jwt.getClaim(CLAIM_TOKEN_TYPE);
		return type != null ? String.valueOf(type) : null;
	}

	public static boolean isInterim(Jwt jwt) {
		return TOKEN_TYPE_INTERIM.equals(tokenTypeFrom(jwt));
	}

	public static boolean isAccess(Jwt jwt) {
		return TOKEN_TYPE_ACCESS.equals(tokenTypeFrom(jwt));
	}
}
