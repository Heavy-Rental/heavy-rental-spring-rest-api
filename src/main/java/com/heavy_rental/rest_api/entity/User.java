package com.heavy_rental.rest_api.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

	public enum UserRole {
		CUSTOMER, ADMIN, DRIVER
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, length = 100)
	private String username;

	@Column(nullable = false)
	private String password;

	@Column(length = 255)
	private String email;

	@Column(length = 255)
	private String company;

	// Have to change to use UserRole enum instead of String for role
	/** Spring Security authority, e.g. ROLE_USER or ROLE_ADMIN */
	@Column(nullable = false, length = 50)
	@Builder.Default
	private String role = "ROLE_USER";

	@Column(nullable = false)
	@Builder.Default
	private boolean enabled = true;

	@Column(name = "created_at")
	private LocalDateTime createdAt;

}
