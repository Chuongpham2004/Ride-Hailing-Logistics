package com.rhl.pricing.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Worked examples of the fare formula (FR-PRI); amounts in VND. */
class FareCalculatorTest {

    /** The placeholder RIDE tariff seeded by V1__init_pricing_schema.sql. */
    private static final Tariff RIDE = new Tariff(12_000, 4_300, 350, 15_000);
    private static final BigDecimal NO_SURGE = new BigDecimal("1.00");

    @Test
    void meteredFareRoundedUpToTheStep() {
        // 5 km, 15 min: 12 000 + 21 500 + 5 250 = 38 750 -> 39 000.
        FareBreakdown fare = FareCalculator.calculate(RIDE, route(5_000, 900), NO_SURGE, 1_000);

        assertThat(fare.baseFare()).isEqualTo(12_000);
        assertThat(fare.distanceFare()).isEqualTo(21_500);
        assertThat(fare.timeFare()).isEqualTo(5_250);
        assertThat(fare.minimumFareAdjustment()).isZero();
        assertThat(fare.surgeAmount()).isZero();
        assertThat(fare.roundingAdjustment()).isEqualTo(250);
        assertThat(fare.total()).isEqualTo(39_000);
    }

    @Test
    void shortTripsPayTheMinimumFare() {
        // 300 m, 1 min: 12 000 + 1 290 + 350 = 13 640, lifted to 15 000.
        FareBreakdown fare = FareCalculator.calculate(RIDE, route(300, 60), NO_SURGE, 1_000);

        assertThat(fare.minimumFareAdjustment()).isEqualTo(1_360);
        assertThat(fare.total()).isEqualTo(15_000);
        assertThat(fare.roundingAdjustment()).isZero();
    }

    @Test
    void surgeAppliesToTheFareAfterTheMinimum() {
        // 38 750 x 1.5 = 58 125 -> 59 000.
        FareBreakdown fare = FareCalculator.calculate(RIDE, route(5_000, 900), new BigDecimal("1.50"), 1_000);

        assertThat(fare.surgeAmount()).isEqualTo(19_375);
        assertThat(fare.roundingAdjustment()).isEqualTo(875);
        assertThat(fare.total()).isEqualTo(59_000);
        assertThat(fare.surgeMultiplier()).isEqualByComparingTo("1.50");
    }

    @ParameterizedTest
    @CsvSource({
            // meters, seconds, distance fare, time fare (half-up to whole VND)
            "1234, 75, 5306, 438",
            "1001, 31, 4304, 181",
            "999, 89, 4296, 519"})
    void fractionsRoundHalfUpPerComponent(int meters, int seconds, long distanceFare, long timeFare) {
        FareBreakdown fare = FareCalculator.calculate(RIDE, route(meters, seconds), NO_SURGE, 1);

        assertThat(fare.distanceFare()).isEqualTo(distanceFare);
        assertThat(fare.timeFare()).isEqualTo(timeFare);
        assertThat(fare.roundingAdjustment()).isZero();
    }

    @Test
    void componentsAlwaysAddUpAndTheSameInputGivesTheSamePrice() {
        Random random = new Random(42);
        for (int i = 0; i < 1_000; i++) {
            RouteEstimate route = route(1 + random.nextInt(100_000), 1 + random.nextInt(10_000));
            BigDecimal surge = BigDecimal.valueOf(100 + random.nextInt(200), 2);
            long step = random.nextBoolean() ? 1_000 : 500;

            FareBreakdown fare = FareCalculator.calculate(RIDE, route, surge, step);

            assertThat(fare.baseFare() + fare.distanceFare() + fare.timeFare() + fare.minimumFareAdjustment()
                    + fare.surgeAmount() + fare.roundingAdjustment()).isEqualTo(fare.total());
            assertThat(fare.total() % step).isZero();
            assertThat(fare.total()).isGreaterThanOrEqualTo(RIDE.minimumFare());
            assertThat(fare.roundingAdjustment()).isBetween(0L, step - 1);
            assertThat(FareCalculator.calculate(RIDE, route, surge, step)).isEqualTo(fare);
        }
    }

    @Test
    void surgeNeverLowersAPrice() {
        assertThatThrownBy(() -> FareCalculator.calculate(RIDE, route(5_000, 900), new BigDecimal("0.90"), 1_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Tariff(-1, 0, 0, 0)).isInstanceOf(DomainException.class);
    }

    private static RouteEstimate route(int meters, int seconds) {
        return new RouteEstimate(meters, seconds, "ESTIMATE");
    }
}
