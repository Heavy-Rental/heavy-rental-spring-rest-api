package com.heavy_rental.rest_api.util;

import java.util.regex.Pattern;

/**
 * Singapore postal code helpers, shared by {@code RentalPlanService}, {@code DistanceService},
 * and {@code PostalCodeService} (see {@code openspec/changes/pricing-postal-distance/}).
 * <p>
 * Does not replace the existing {@code @Pattern(".*\\d{6}$")} suffix validation already on
 * {@code RentalPlanCreateRequest}/{@code CreateBookingRequest}/{@code BookingUpdateRequest} —
 * those stay as-is; this is for the new call sites that need the postal code on its own.
 */
public final class PostalCodeUtil {

	private static final Pattern WELL_FORMED = Pattern.compile("^\\d{6}$");

	private PostalCodeUtil() {
	}

	/** True iff {@code postalCode} is exactly 6 digits (not a check that it actually exists). */
	public static boolean isWellFormed(String postalCode) {
		return postalCode != null && WELL_FORMED.matcher(postalCode).matches();
	}

	/**
	 * Last 6 characters of {@code siteAddress}, stripped of surrounding whitespace first — the
	 * Java equivalent of {@code Booking.sitePostalCode}'s SQL {@code @Formula} substring logic.
	 * Returns {@code null} if {@code siteAddress} is {@code null}, too short, or its trailing 6
	 * characters aren't actually 6 digits (defensive; the DTOs already enforce this on input).
	 */
	public static String extractTrailing6Digits(String siteAddress) {
		if (siteAddress == null) {
			return null;
		}
		String trimmed = siteAddress.strip();
		if (trimmed.length() < 6) {
			return null;
		}
		String candidate = trimmed.substring(trimmed.length() - 6);
		return isWellFormed(candidate) ? candidate : null;
	}
}
