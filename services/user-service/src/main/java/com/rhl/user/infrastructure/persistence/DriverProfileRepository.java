package com.rhl.user.infrastructure.persistence;

import com.rhl.user.domain.driver.DriverProfile;
import com.rhl.user.domain.driver.ReviewStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Review queues use keyset pagination by driver ID; UUIDv7 IDs sort by creation time. */
public interface DriverProfileRepository extends JpaRepository<DriverProfile, UUID> {

    List<DriverProfile> findByReviewStatusOrderByDriverId(ReviewStatus status, Limit limit);

    List<DriverProfile> findByReviewStatusAndDriverIdGreaterThanOrderByDriverId(ReviewStatus status, UUID after,
                                                                                 Limit limit);
}
