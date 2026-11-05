package com.rhl.user.application.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserSearchTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void blankMatchesEveryone(String raw) {
        assertThat(UserSearch.of(raw)).isEqualTo(UserSearch.ANY);
    }

    @Test
    void anIdIsLookedUpExactly() {
        UUID id = UUID.fromString("01a1aae3-1111-7000-8000-000000000001");
        assertThat(UserSearch.of(" 01A1AAE3-1111-7000-8000-000000000001 "))
                .isEqualTo(new UserSearch(id, null, null));
    }

    @Test
    void textMatchesEmailAndNameInLowerCase() {
        UserSearch search = UserSearch.of("  Trần Văn ");
        assertThat(search.text()).isEqualTo("%trần văn%");
        assertThat(search.phone()).isNull();
    }

    @Test
    void likeWildcardsAreTakenLiterally() {
        assertThat(UserSearch.of("50%_off!").text()).isEqualTo("%50!%!_off!!%");
    }

    @Test
    void aLocalPhoneNumberMatchesTheStoredE164Form() {
        assertThat(UserSearch.of("0912 345.678").phone()).isEqualTo("%912345678%");
        assertThat(UserSearch.of("+84 912").phone()).isEqualTo("%84912%");
    }

    @Test
    void tooFewDigitsDoNotSearchPhones() {
        assertThat(UserSearch.of("09").phone()).isNull();
        assertThat(UserSearch.of("an@rhl.vn").phone()).isNull();
    }
}
