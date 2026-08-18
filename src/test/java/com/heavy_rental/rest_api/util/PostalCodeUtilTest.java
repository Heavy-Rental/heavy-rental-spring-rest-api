package com.heavy_rental.rest_api.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PostalCodeUtil")
class PostalCodeUtilTest {

	@Test
	@DisplayName("isWellFormed: exactly 6 digits is well-formed")
	void isWellFormed_sixDigits_true() {
		assertTrue(PostalCodeUtil.isWellFormed("619094"));
		assertTrue(PostalCodeUtil.isWellFormed("000000"));
	}

	@Test
	@DisplayName("isWellFormed: null, wrong length, or non-digits are not well-formed")
	void isWellFormed_invalidInputs_false() {
		assertFalse(PostalCodeUtil.isWellFormed(null));
		assertFalse(PostalCodeUtil.isWellFormed(""));
		assertFalse(PostalCodeUtil.isWellFormed("12345"));
		assertFalse(PostalCodeUtil.isWellFormed("1234567"));
		assertFalse(PostalCodeUtil.isWellFormed("61909A"));
		assertFalse(PostalCodeUtil.isWellFormed(" 619094"));
		assertFalse(PostalCodeUtil.isWellFormed("619094 "));
	}

	@Test
	@DisplayName("extractTrailing6Digits: pulls the trailing 6-digit postal code out of a full address")
	void extractTrailing6Digits_fullAddress_returnsPostalCode() {
		assertEquals("619094", PostalCodeUtil.extractTrailing6Digits("20 Jurong Port Road, 619094"));
		assertEquals("629462", PostalCodeUtil.extractTrailing6Digits("11 Gul Drive, Singapore 629462"));
	}

	@Test
	@DisplayName("extractTrailing6Digits: strips surrounding whitespace before extracting")
	void extractTrailing6Digits_surroundingWhitespace_isStripped() {
		assertEquals("619094", PostalCodeUtil.extractTrailing6Digits("  20 Jurong Port Road, 619094  "));
	}

	@Test
	@DisplayName("extractTrailing6Digits: null, too short, or a non-digit tail returns null")
	void extractTrailing6Digits_invalidInputs_returnsNull() {
		assertNull(PostalCodeUtil.extractTrailing6Digits(null));
		assertNull(PostalCodeUtil.extractTrailing6Digits("abc"));
		assertNull(PostalCodeUtil.extractTrailing6Digits("20 Jurong Port Road"));
		assertNull(PostalCodeUtil.extractTrailing6Digits("20 Jurong Port Road, 61909A"));
	}
}
