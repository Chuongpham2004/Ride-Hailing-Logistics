package com.rhl.trip.application;

import com.rhl.trip.infrastructure.cache.DriverHoldStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Side effects that must only happen once the database change is committed: releasing a Redis
 * hold and starting the next dispatch round. If either is lost (crash, Redis down), the hold
 * expires on its own and the scheduler picks the trip up on its next tick.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AfterCommit {

    /** Published inside a transaction: drop the driver's hold for this offer after commit. */
    public record ReleaseHold(UUID driverId, UUID offerId) {
    }

    /** Published inside a transaction: run a dispatch round for the trip after commit. */
    public record Dispatch(UUID tripId) {
    }

    private final DriverHoldStore holds;
    private final Dispatcher dispatcher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReleaseHold(ReleaseHold event) {
        try {
            holds.release(event.driverId(), event.offerId());
        } catch (RuntimeException e) {
            log.warn("Could not release driver hold for offer {}, it will expire: {}", event.offerId(),
                    e.getMessage());
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDispatch(Dispatch event) {
        dispatcher.dispatch(event.tripId());
    }
}
