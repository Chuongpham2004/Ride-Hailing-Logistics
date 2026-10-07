package com.rhl.user.domain.user;

import com.rhl.common.security.Role;
import com.rhl.user.domain.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContactVerificationTest {

    private static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z");

    @ParameterizedTest
    @CsvSource({
            "an@example.com, a***@example.com",
            "+84912345678, +849***678",
            "+8412, ***"})
    void addressesAreMaskedButRecognisable(String address, String masked) {
        assertThat(ContactChannel.mask(address)).isEqualTo(masked);
    }

    @Test
    void channelsComeFromThePathOrTheIdentifier() {
        assertThat(ContactChannel.parse("email")).isEqualTo(ContactChannel.EMAIL);
        assertThat(ContactChannel.parse("PHONE")).isEqualTo(ContactChannel.PHONE);
        assertThatThrownBy(() -> ContactChannel.parse("fax")).isInstanceOf(DomainException.class);
        assertThat(ContactChannel.of("a@b.vn")).isEqualTo(ContactChannel.EMAIL);
        assertThat(ContactChannel.of("+84912345678")).isEqualTo(ContactChannel.PHONE);
    }

    @Test
    void onlyAnExistingContactCanBeVerified() {
        User user = User.register("a@b.vn", null, "hash", "Nguyen Van A", Role.DRIVER, NOW);
        assertThat(user.hasVerifiedContact()).isFalse();
        assertThatThrownBy(() -> user.markVerified(ContactChannel.PHONE, NOW)).isInstanceOf(DomainException.class);

        user.markVerified(ContactChannel.EMAIL, NOW);

        assertThat(user.isVerified(ContactChannel.EMAIL)).isTrue();
        assertThat(user.isVerified(ContactChannel.PHONE)).isFalse();
        assertThat(user.hasVerifiedContact()).isTrue();
    }

    @Test
    void aResetChangesThePasswordAndProvesTheAddressThatReceivedTheCode() {
        User user = User.register(null, "+84912345678", "old", "Nguyen Van B", Role.CUSTOMER, NOW);

        user.resetPassword("new", ContactChannel.PHONE, NOW);

        assertThat(user.getPasswordHash()).isEqualTo("new");
        assertThat(user.getPasswordChangedAt()).isEqualTo(NOW);
        assertThat(user.isVerified(ContactChannel.PHONE)).isTrue();
    }
}
