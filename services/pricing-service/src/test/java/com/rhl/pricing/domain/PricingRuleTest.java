package com.rhl.pricing.domain;

import com.rhl.pricing.PricingServiceProperties;
import com.rhl.pricing.infrastructure.route.EstimatedRouteProvider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PricingRuleTest {

    private static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");
    private static final Tariff TARIFF = new Tariff(12_000, 4_300, 350, 15_000);

    @Test
    void aNewVersionTakesOverAtItsStartAndClosesThePreviousOne() {
        PricingRule v1 = rule(NOW.minus(Duration.ofDays(30)));
        Instant from = NOW.plus(Duration.ofHours(1));

        PricingRule v2 = v1.supersede(UUID.randomUUID(), new Tariff(13_000, 4_500, 400, 16_000), from, null, NOW);

        assertThat(v2.getVersion()).isEqualTo(2);
        assertThat(v1.getEffectiveTo()).isEqualTo(from);
        assertThat(v1.isEffectiveAt(from.minusMillis(1))).isTrue();
        assertThat(v1.isEffectiveAt(from)).isFalse();
        assertThat(v2.isEffectiveAt(from)).isTrue();
        assertThat(v2.isEffectiveAt(from.minusMillis(1))).isFalse();
        assertThat(v2.tariff().baseFare()).isEqualTo(13_000);
    }

    @Test
    void pricesCannotChangeRetroactively() {
        PricingRule v1 = rule(NOW.minus(Duration.ofDays(30)));

        assertThatThrownBy(() -> v1.supersede(UUID.randomUUID(), TARIFF, NOW, null, NOW))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> v1.supersede(UUID.randomUUID(), TARIFF, NOW.minusSeconds(60), null, NOW))
                .isInstanceOf(DomainException.class);
        assertThat(v1.getEffectiveTo()).isNull();
    }

    @Test
    void aScheduledRuleCannotBeOvertakenByAnEarlierOne() {
        PricingRule v2 = rule(NOW.plus(Duration.ofDays(2)));

        assertThatThrownBy(() -> v2.supersede(UUID.randomUUID(), TARIFF, NOW.plus(Duration.ofDays(1)), null, NOW))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("start after");
    }

    @Test
    void estimatedRouteUsesRoadFactorAndAverageSpeed() {
        EstimatedRouteProvider provider = new EstimatedRouteProvider(new PricingServiceProperties(
                new PricingServiceProperties.Quote(Duration.ofMinutes(5), 1_000, 200, 100_000),
                new PricingServiceProperties.Route(1.35, 22, 60),
                new PricingServiceProperties.Surge(8, 1, Duration.ofMinutes(5), Duration.ofSeconds(60)),
                new PricingServiceProperties.Kafka(3, (short) 1, 1), "DEFAULT"));
        // Ben Thanh market -> University of Science, District 5: about 2.0 km as the crow flies.
        Stop pickup = new Stop(10.7725, 106.6980, "Ben Thanh");
        Stop dropoff = new Stop(10.7626, 106.6822, "DH KHTN");

        RouteEstimate route = provider.route(ServiceType.RIDE, pickup, dropoff);

        double straight = GeoDistance.meters(pickup, dropoff);
        assertThat(straight).isBetween(1_950.0, 2_150.0);
        assertThat(route.distanceMeters()).isEqualTo((int) Math.round(straight * 1.35));
        assertThat(route.durationSeconds()).isEqualTo((int) Math.round(route.distanceMeters() / (22 / 3.6)));
        assertThat(route.source()).isEqualTo("ESTIMATE");
        // Very short hops still take the minimum duration.
        assertThat(provider.route(ServiceType.RIDE, pickup, new Stop(10.7726, 106.6980, "x")).durationSeconds())
                .isEqualTo(60);
    }

    private static PricingRule rule(Instant from) {
        return PricingRule.create(UUID.randomUUID(), ServiceType.RIDE, "DEFAULT", 1, TARIFF, "VND", from, null,
                from.minusSeconds(1));
    }
}
