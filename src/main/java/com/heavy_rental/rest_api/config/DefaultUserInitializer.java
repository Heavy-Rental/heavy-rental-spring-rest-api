package com.heavy_rental.rest_api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.heavy_rental.rest_api.entity.User;
import com.heavy_rental.rest_api.repository.UserRepository;

/**
 * Seeds a default admin user when the users table is empty (local/dev convenience).
 */
@Component
public class DefaultUserInitializer implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(DefaultUserInitializer.class);

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final String defaultUsername;
	private final String defaultPassword;

	public DefaultUserInitializer(
			UserRepository userRepository,
			PasswordEncoder passwordEncoder,
			@Value("${app.security.default-username}") String defaultUsername,
			@Value("${app.security.default-password}") String defaultPassword) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.defaultUsername = defaultUsername;
		this.defaultPassword = defaultPassword;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (userRepository.count() > 0) {
			return;
		}

		User admin = User.builder()
				.username(defaultUsername)
				.password(passwordEncoder.encode(defaultPassword))
				.email(defaultUsername + "@localhost")
				.role("ROLE_ADMIN")
				.enabled(true)
				.build();

		userRepository.save(admin);
		log.info("Created default user '{}' (change DEFAULT_PASSWORD in production)", defaultUsername);
	}
}
