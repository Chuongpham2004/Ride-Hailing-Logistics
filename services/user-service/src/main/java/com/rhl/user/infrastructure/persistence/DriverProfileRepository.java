package com.rhl.user.infrastructure.persistence;

import com.rhl.user.domain.driver.DriverProfile;
import com.rhl.user.domain.driver.ReviewStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Review queues use keyset pagination by driver ID; UUIDv7 IDs sort by creation time. */
public interface DriverProfileRepository extends JpaRepository<DriverProfile, UUID> {

    List<DriverProfile> findByReviewStatusOrderByDriverId(ReviewStatus status, Limit limit);

    /** OFFERED drivers whose offer ran out before {@code cutoff}, or never recorded an expiry. */
    @Query(value = """
            SELECT driver_id FROM driver_profiles
            WHERE availability = 'OFFERED'
              AND (current_offer_expires_at IS NULL OR current_offer_expires_at < :cutoff)
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findStaleOffered(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    List<DriverProfile> findByReviewStatusAndDriverIdGreaterThanOrderByDriverId(ReviewStatus status, UUID after,
                                                                                 Limit limit);
}
