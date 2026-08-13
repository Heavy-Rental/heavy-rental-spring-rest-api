package com.heavy_rental.rest_api.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;

import com.heavy_rental.rest_api.entity.Asset;

// Only implementation until a FastAPI-backed PricingClient replaces it
// (see openspec/specs/rental-plan-quote/ and spring-proxy-endpoints).
@Service
public class DefaultPricingClient implements PricingClient {

    @Override
    public ItemPrice priceItem(Asset asset, LocalDate startDate, LocalDate endDate) {
        long days = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        BigDecimal dailyRate = asset.getBaseDailyRate();
        BigDecimal subtotal = dailyRate.multiply(BigDecimal.valueOf(days));
        return new ItemPrice(dailyRate, subtotal);
    }
}
