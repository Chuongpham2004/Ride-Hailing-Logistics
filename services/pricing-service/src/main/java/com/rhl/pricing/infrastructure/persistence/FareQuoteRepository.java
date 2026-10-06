package com.rhl.pricing.infrastructure.persistence;

import com.rhl.pricing.domain.FareQuote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FareQuoteRepository extends JpaRepository<FareQuote, UUID> {
}
