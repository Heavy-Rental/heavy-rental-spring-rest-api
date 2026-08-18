package com.heavy_rental.rest_api.client.onemap;

/** A geocoded postal code — coordinates plus OneMap's resolved address string. */
public record Coordinates(double latitude, double longitude, String address) {
}
