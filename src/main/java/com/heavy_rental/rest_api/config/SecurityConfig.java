package com.heavy_rental.rest_api.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.heavy_rental.rest_api.security.TokenDenylist;

import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

	private final ObjectMapper objectMapper;
	private final JwtProperties jwtProperties;
	private final TokenDenylist tokenDenylist;

	public SecurityConfig(
			ObjectMapper objectMapper,
			JwtProperties jwtProperties,
			TokenDenylist tokenDenylist) {
		this.objectMapper = objectMapper;
		this.jwtProperties = jwtProperties;
		this.tokenDenylist = tokenDenylist;
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
			.csrf(csrf -> csrf.disable())
			.cors(Customizer.withDefaults())
			.sessionManagement(session -> session
				.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers(HttpMethod.GET, "/api/auth/getBearerToken").permitAll()
				.requestMatchers(HttpMethod.POST, "/api/auth/login").hasAuthority("ROLE_INTERIM")
				.requestMatchers(HttpMethod.POST, "/api/auth/logout")
					.hasAnyAuthority("ROLE_USER", "ROLE_ADMIN")
				.requestMatchers("/error").permitAll()
				.requestMatchers("/actuator/health", "/actuator/info").permitAll()
				.anyRequest().hasAnyAuthority("ROLE_USER", "ROLE_ADMIN"))
			.oauth2ResourceServer(oauth2 -> oauth2
				.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
				.authenticationEntryPoint(restAuthenticationEntryPoint()))
			.exceptionHandling(ex -> ex
				.authenticationEntryPoint(restAuthenticationEntryPoint())
				.accessDeniedHandler(restAccessDeniedHandler()));

		return http.build();
	}

	@Bean
	JwtEncoder jwtEncoder() {
		return NimbusJwtEncoder.withSecretKey(secretKey())
				.algorithm(MacAlgorithm.HS256)
				.build();
	}

	@Bean
	JwtDecoder jwtDecoder() {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey())
				.macAlgorithm(MacAlgorithm.HS256)
				.build();

		OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(jwtProperties.issuer());
		OAuth2TokenValidator<Jwt> denylistValidator = token -> {
			if (tokenDenylist.isDenied(token.getId())) {
				return OAuth2TokenValidatorResult.failure(
						new OAuth2Error("invalid_token", "Token has been revoked", null));
			}
			return OAuth2TokenValidatorResult.success();
		};

		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(withIssuer, denylistValidator));
		return decoder;
	}

	@Bean
	JwtAuthenticationConverter jwtAuthenticationConverter() {
		JwtGrantedAuthoritiesConverter rolesConverter = new JwtGrantedAuthoritiesConverter();
		// Roles are stored without the SCOPE_ prefix; claim name is "roles"
		rolesConverter.setAuthoritiesClaimName("roles");
		rolesConverter.setAuthorityPrefix("");

		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(rolesConverter);
		return converter;
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
		return configuration.getAuthenticationManager();
	}

	@Bean
	AuthenticationEntryPoint restAuthenticationEntryPoint() {
		return (request, response, authException) -> writeJson(
				response,
				HttpServletResponse.SC_UNAUTHORIZED,
				Map.of(
						"error", "unauthorized",
						"message",
						"Authentication required. Obtain an interim token via GET /api/auth/getBearerToken, "
								+ "login via POST /api/auth/login with that Bearer token, "
								+ "then use the returned access token as Authorization: Bearer <token>."));
	}

	@Bean
	AccessDeniedHandler restAccessDeniedHandler() {
		return (request, response, accessDeniedException) -> writeJson(
				response,
				HttpServletResponse.SC_FORBIDDEN,
				Map.of(
						"error", "forbidden",
						"message", "You do not have permission to access this resource"));
	}

	private SecretKey secretKey() {
		byte[] secretBytes = jwtProperties.secret().getBytes(StandardCharsets.UTF_8);
		if (secretBytes.length < 32) {
			throw new IllegalStateException(
					"app.jwt.secret must be at least 32 characters (256 bits) for HS256");
		}
		return new SecretKeySpec(secretBytes, "HmacSHA256");
	}

	private void writeJson(HttpServletResponse response, int status, Map<String, ?> body) throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		objectMapper.writeValue(response.getOutputStream(), new LinkedHashMap<>(body));
	}
}
