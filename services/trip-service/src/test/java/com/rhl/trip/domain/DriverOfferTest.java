package com.rhl.trip.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DriverOfferTest {

    private static final Instant NOW = Instant.parse("2026-10-05T08:30:00Z");

    @Test
    void acceptWithinTheTimeout() {
        DriverOffer offer = offer();

        offer.accept(NOW.plusSeconds(14));

        assertThat(offer.getStatus()).isEqualTo(OfferStatus.ACCEPTED);
        assertThat(offer.getRespondedAt()).isEqualTo(NOW.plusSeconds(14));
    }

    @Test
    void anExpiredOrClosedOfferCannotBeAccepted() {
        DriverOffer late = offer();
        assertThatThrownBy(() -> late.accept(NOW.plusSeconds(15)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).kind())
                .isEqualTo(DomainException.Kind.OFFER_EXPIRED);

        DriverOffer declined = offer();
        declined.decline(NOW.plusSeconds(1));
        assertThatThrownBy(() -> declined.accept(NOW.plusSeconds(2))).isInstanceOf(DomainException.class);

        DriverOffer expired = offer();
        expired.expire(NOW.plusSeconds(15));
        assertThat(expired.getStatus()).isEqualTo(OfferStatus.EXPIRED);
        assertThatThrownBy(() -> expired.withdraw(NOW.plusSeconds(16))).isInstanceOf(DomainException.class);
    }

    @Test
    void candidatesAreRankedByDistanceThenFreshnessAndFiltered() {
        UUID near = UUID.randomUUID();
        UUID nearButStale = UUID.randomUUID();
        UUID far = UUID.randomUUID();
        UUID alreadyTried = UUID.randomUUID();

        List<DriverCandidate> ranked = DriverCandidate.rank(List.of(
                new DriverCandidate(far, 1900, 100),
                new DriverCandidate(nearButStale, 300, 9000),
                new DriverCandidate(alreadyTried, 50, 100),
                new DriverCandidate(near, 300, 1000)), Set.of(alreadyTried));

        assertThat(ranked).extracting(DriverCandidate::driverId).containsExactly(near, nearButStale, far);
    }

    @Test
    void policyRejectsInconsistentSettings() {
        assertThatThrownBy(() -> new MatchingPolicy(3000, 1000, 2000, Duration.ofSeconds(15), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MatchingPolicy(2000, 1000, 8000, Duration.ofSeconds(40), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DriverOffer offer() {
        return DriverOffer.create(UUID.randomUUID(), UUID.randomUUID(),
                new DriverCandidate(UUID.randomUUID(), 850, 1200), Duration.ofSeconds(15), NOW);
    }
}
