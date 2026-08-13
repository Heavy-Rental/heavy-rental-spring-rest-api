package com.heavy_rental.rest_api.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import com.heavy_rental.rest_api.dto.UserCreateRequest;
import com.heavy_rental.rest_api.dto.UserCreateResponse;
import com.heavy_rental.rest_api.dto.UserResponse;
import com.heavy_rental.rest_api.dto.UserUpdateRequest;
import com.heavy_rental.rest_api.service.UserAdminService;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserAdminService userAdminService;

    public UserController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    @GetMapping
    public List<UserResponse> listAll() {
        return userAdminService.listAll();
    }

    @GetMapping("/{id}")
    public UserResponse getById(@PathVariable Long id) {
        return userAdminService.getById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserCreateResponse create(@RequestBody UserCreateRequest request) {
        return userAdminService.create(request);
    }

    @PatchMapping("/{id}")
    public UserResponse update(@PathVariable Long id, @RequestBody UserUpdateRequest request) {
        return userAdminService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Long id) {
        userAdminService.remove(id);
    }
}
