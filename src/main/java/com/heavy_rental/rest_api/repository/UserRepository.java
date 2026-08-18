package com.heavy_rental.rest_api.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.heavy_rental.rest_api.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {

	List<User> findByRole(User.UserRole role);

	Optional<User> findByEmail(String email);

	boolean existsByEmail(String email);
}
