package com.heavy_rental.rest_api.service;

import java.security.SecureRandom;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.heavy_rental.rest_api.dto.UserCreateRequest;
import com.heavy_rental.rest_api.dto.UserCreateResponse;
import com.heavy_rental.rest_api.dto.UserResponse;
import com.heavy_rental.rest_api.dto.UserUpdateRequest;
import com.heavy_rental.rest_api.entity.User;
import com.heavy_rental.rest_api.repository.UserRepository;

@Service
public class UserAdminService {

    private static final String TEMPORARY_PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%";
    private static final int PASSWORD_LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserAdminService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> listAll() {
        return userRepository.findAll().stream()
                .filter(User::isEnabled)
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public UserResponse getById(Long id) {
        User user = findEnabledOrThrow(id);
        return toResponse(user);
    }

    @Transactional
    public UserResponse update(Long id, UserUpdateRequest request) {
        User user = findEnabledOrThrow(id);

        if (request.name() != null) user.setName(request.name());
        if (request.email() != null) user.setEmail(request.email());
        if (request.role() != null) user.setRole(parseRole(request.role()));

        User saved = userRepository.save(user);
        return toResponse(saved);
    }

    @Transactional
    public void remove(Long id) {
        User user = findEnabledOrThrow(id);
        user.setEnabled(false);
        userRepository.save(user);
    }

    @Transactional
    public UserCreateResponse create(UserCreateRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already in use: " + request.email());
        }

        String temporaryPassword = generateTemporaryPassword();

        User user = User.builder()
                .name(request.name())
                .email(request.email())
                .password(passwordEncoder.encode(temporaryPassword))
                .role(User.UserRole.USER)
                .enabled(true)
                .build();

        User saved = userRepository.save(user);

        return new UserCreateResponse(
                saved.getId(),
                saved.getName(),
                saved.getEmail(),
                roleToFrontend(saved.getRole()),
                temporaryPassword);
    }

    private User findEnabledOrThrow(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (!user.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        return user;
    }

    private String generateTemporaryPassword() {
        StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            sb.append(TEMPORARY_PASSWORD_ALPHABET.charAt(RANDOM.nextInt(TEMPORARY_PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                roleToFrontend(user.getRole()));
    }

    // Frontend role strings ("customer"/"employee"/"admin") don't match this codebase's
    // pre-existing UserRole enum (USER/ADMIN/DRIVER) — mapping decided and confirmed against
    // the live frontend's Role type and role <select> options (see SPEC discussion).
    private String roleToFrontend(User.UserRole role) {
        return switch (role) {
            case USER -> "customer";
            case DRIVER -> "employee";
            case ADMIN -> "admin";
        };
    }

    private User.UserRole parseRole(String role) {
        return switch (role) {
            case "customer" -> User.UserRole.USER;
            case "employee" -> User.UserRole.DRIVER;
            case "admin" -> User.UserRole.ADMIN;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown role: " + role);
        };
    }
}
