package com.rhl.user.domain.user;

import com.rhl.user.domain.DomainException;
import com.rhl.user.domain.driver.Vehicle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NormalizationTest {

    @ParameterizedTest
    @CsvSource({
            "' An.Nguyen@Example.COM ', an.nguyen@example.com",
            "a@b.vn, a@b.vn"})
    void normalizesEmails(String raw, String expected) {
        assertThat(Identifiers.email(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "0912345678, +84912345678",
            "'091 234 5678', +84912345678",
            "84912345678, +84912345678",
            "+84912345678, +84912345678",
            "+14155550100, +14155550100"})
    void normalizesPhonesToE164(String raw, String expected) {
        assertThat(Identifiers.phone(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-email", "a@b", "0123", "abc", "+0123456789"})
    void rejectsInvalidIdentifiers(String raw) {
        assertThatThrownBy(() -> Identifiers.loginIdentifier(raw)).isInstanceOf(DomainException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "51F-123.45, 51F12345",
            "'59x3 123.45', 59X312345",
            "29a12345, 29A12345"})
    void normalizesPlates(String raw, String expected) {
        assertThat(Vehicle.normalizePlate(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABC", "5F12345", "51F12"})
    void rejectsInvalidPlates(String raw) {
        assertThatThrownBy(() -> Vehicle.normalizePlate(raw)).isInstanceOf(DomainException.class);
    }
}
