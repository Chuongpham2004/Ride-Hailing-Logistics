package com.rhl.common.id;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class UuidV7Test {

    @Test
    void idsAreVersion7AndStrictlyIncreasingEvenWithinOneMillisecond() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 200_000; i++) {
            UUID id = UuidV7.random();
            assertThat(id.version()).isEqualTo(7);
            assertThat(id.variant()).isEqualTo(2);
            ids.add(id.toString());
        }
        // Lowercase hex strings sort like the 16 bytes PostgreSQL compares.
        for (int i = 1; i < ids.size(); i++) {
            assertThat(ids.get(i)).isGreaterThan(ids.get(i - 1));
        }
    }

    @Test
    void timestampStaysCloseToTheClock() {
        long before = System.currentTimeMillis();
        long millis = UuidV7.random().getMostSignificantBits() >>> 16;

        // At most a few milliseconds ahead, even right after a burst of IDs.
        assertThat(millis).isBetween(before, System.currentTimeMillis() + 100);
    }

    @Test
    void concurrentCallersNeverGetTheSameId() throws Exception {
        Set<UUID> seen = ConcurrentHashMap.newKeySet();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 20_000; i++) {
                        assertThat(seen.add(UuidV7.random())).isTrue();
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        }
        assertThat(seen).hasSize(160_000);
    }
}
