package com.heavy_rental.rest_api.service;

import java.util.List;

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

    /**
     * Same as {@link #assertOwnerOrAdmin}, but also bypasses ownership for {@code ROLE_DRIVER} —
     * for the mobile ops app's booking screens, where drivers (not just admins) legitimately need
     * every customer's booking, not only their own. Deliberately separate from
     * {@code assertOwnerOrAdmin}, which stays admin-only for payments/recommendations, where a
     * driver has no business bypassing ownership.
     */
    public void assertOwnerOrStaff(Jwt jwt, User owner) {
        List<String> roles = JwtService.rolesFrom(jwt);
        if (roles.contains("ROLE_ADMIN") || roles.contains("ROLE_DRIVER")) {
            return;
        }
        String email = jwt.getSubject();
        if (owner == null || owner.getEmail() == null || !owner.getEmail().equalsIgnoreCase(email)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have access to this resource");
        }
    }
}
