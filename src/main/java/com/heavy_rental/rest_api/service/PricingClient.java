package com.heavy_rental.rest_api.service;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.heavy_rental.rest_api.entity.Asset;

public interface PricingClient {

    record ItemPrice(BigDecimal dailyRate, BigDecimal subtotal) {
    }

    ItemPrice priceItem(Asset asset, LocalDate startDate, LocalDate endDate);
}
