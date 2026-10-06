package com.rhl.pricing.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** The placeholder policy seeded by V3 (TBD-07): 120 s free window, 10 000 late, 15 000 no-show. */
class CancellationFeeRuleTest {

    private static final CancellationFeeRule RULE = CancellationFeeRule.of(ServiceType.RIDE, 1, 120, 10_000, 15_000);
    private static final Instant ACCEPTED = Instant.parse("2026-10-06T08:00:00Z");

    @ParameterizedTest
    @CsvSource({
            // actor, old status, reason, seconds after acceptance, decision, fee
            "CUSTOMER, MATCHING, CHANGED_MIND, 600, NOT_ASSIGNED, 0",
            "CUSTOMER, CREATED, CHANGED_MIND, 600, NOT_ASSIGNED, 0",
            "STAFF, MATCHING, OTHER, 600, NOT_ASSIGNED, 0",
            "CUSTOMER, ACCEPTED, CHANGED_MIND, 60, WITHIN_FREE_WINDOW, 0",
            "CUSTOMER, PICKING_UP, CHANGED_MIND, 120, WITHIN_FREE_WINDOW, 0",
            "CUSTOMER, PICKING_UP, WAIT_TOO_LONG, 121, LATE_CANCELLATION, 10000",
            "CUSTOMER, ARRIVED, CHANGED_MIND, 900, LATE_CANCELLATION, 10000",
            "DRIVER, ARRIVED, CUSTOMER_NO_SHOW, 900, NO_SHOW, 15000",
            "DRIVER, PICKING_UP, CUSTOMER_NO_SHOW, 900, NOT_CHARGEABLE, 0",
            "DRIVER, ACCEPTED, VEHICLE_ISSUE, 900, NOT_CHARGEABLE, 0",
            "STAFF, IN_TRIP, SAFETY_CONCERN, 900, NOT_CHARGEABLE, 0"})
    void onlyCustomersPayAndOnlyOnceADriverWasCommitted(String actor, String oldStatus, String reason, int seconds,
                                                      String decision, long fee) {
        CancellationFeeRule.Decision result = RULE.decide(new CancellationFeeRule.Cancellation(actor, oldStatus, reason,
                ACCEPTED, ACCEPTED.plusSeconds(seconds), 27_000L));

        assertThat(result.decision()).isEqualTo(decision);
        assertThat(result.fee()).isEqualTo(fee);
    }

    @Test
    void aFeeNeverExceedsTheBookedFare() {
        CancellationFeeRule.Decision result = RULE.decide(new CancellationFeeRule.Cancellation("DRIVER", "ARRIVED",
                "CUSTOMER_NO_SHOW", ACCEPTED, ACCEPTED.plusSeconds(900), 12_000L));

        assertThat(result).isEqualTo(new CancellationFeeRule.Decision("NO_SHOW", 12_000));
    }

    @Test
    void withoutAnAcceptanceTimeTheCustomerIsNotCharged() {
        CancellationFeeRule.Decision result = RULE.decide(new CancellationFeeRule.Cancellation("CUSTOMER", "ARRIVED",
                "CHANGED_MIND", null, ACCEPTED.plusSeconds(900), null));

        assertThat(result.decision()).isEqualTo("WITHIN_FREE_WINDOW");
        assertThat(result.fee()).isZero();
    }
}
