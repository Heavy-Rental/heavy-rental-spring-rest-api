package com.heavy_rental.rest_api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// Also activates TokenDenylist's existing @Scheduled cleanup job, which was inert before
// this was added since @EnableScheduling didn't exist anywhere in the app.
@SpringBootApplication
@EnableScheduling
public class RestApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(RestApiApplication.class, args);
	}

}
