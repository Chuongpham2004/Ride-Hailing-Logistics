package com.rhl.user.infrastructure.persistence;

import com.rhl.user.domain.driver.ReviewDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReviewDecisionRepository extends JpaRepository<ReviewDecision, UUID> {

    List<ReviewDecision> findByDriverIdOrderByDecidedAt(UUID driverId);
}
