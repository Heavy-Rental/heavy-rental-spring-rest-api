package com.heavy_rental.rest_api.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Prod only: optional {@code data.sql} after Flyway when {@code APP_SEED_DATA_SQL=true}. */
@Configuration
@Profile("prod")
class FlywayConfig {

	@Bean
	FlywayMigrationStrategy flywayMigrationStrategy(
			DataSource dataSource,
			ResourceLoader resourceLoader,
			@Value("${app.seed.data-sql:false}") boolean seedDataSql) {
		return flyway -> {
			flyway.migrate();
			if (!seedDataSql) {
				return;
			}
			Resource resource = resourceLoader.getResource("classpath:data.sql");
			if (!resource.exists()) {
				throw new IllegalStateException("app.seed.data-sql=true but classpath:data.sql is missing");
			}
			ResourceDatabasePopulator populator = new ResourceDatabasePopulator(resource);
			DatabasePopulatorUtils.execute(populator, dataSource);
		};
	}
}
