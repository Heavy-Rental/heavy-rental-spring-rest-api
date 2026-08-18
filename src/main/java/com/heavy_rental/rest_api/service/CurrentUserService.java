package com.heavy_rental.rest_api.service;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.heavy_rental.rest_api.entity.User;
import com.heavy_rental.rest_api.repository.UserRepository;
import com.heavy_rental.rest_api.security.JwtService;

/**
 * Resolves the authenticated JWT principal (subject = email) to a {@link User},
 * and enforces ownership checks shared across booking and payment endpoints.
 */
@Service
public class CurrentUserService {

    private final UserRepository userRepository;

    public CurrentUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User getUser(Jwt jwt) {
        return userRepository.findByEmail(jwt.getSubject())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    public void assertOwnerOrAdmin(Jwt jwt, User owner) {
        if (JwtService.rolesFrom(jwt).contains("ROLE_ADMIN")) {
            return;
        }
        String email = jwt.getSubject();
        if (owner == null || owner.getEmail() == null || !owner.getEmail().equalsIgnoreCase(email)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have access to this resource");
        }
    }
}
