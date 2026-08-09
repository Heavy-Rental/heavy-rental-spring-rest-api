package com.heavy_rental.rest_api.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Stub: this backend has no Depot entity — delivery locations are plain fields on
// Booking/RentalPlan (siteAddress/sitePostalCode/etc.), not a separate resource.
// Exists only so the frontend's CustomerPortal (which errors its whole equipment
// page if either /api/equipment or /api/depots fails) doesn't break on this call.
@RestController
@RequestMapping("/api/depots")
public class DepotController {

    @GetMapping
    public List<Object> list() {
        return List.of();
    }
}
