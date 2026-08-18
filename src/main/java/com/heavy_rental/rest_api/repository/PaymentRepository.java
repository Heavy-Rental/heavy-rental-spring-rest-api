package com.heavy_rental.rest_api.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.heavy_rental.rest_api.entity.Payment;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
  List<Payment> findByBookingId(Long bookingId);
  List<Payment> findByStatus(Payment.PaymentStatus status);
  Optional<Payment> findByStripePaymentIntentId(String stripePaymentIntentId);
}
