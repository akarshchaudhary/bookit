package com.seatreserve.repo;

import com.seatreserve.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {
    Optional<IdempotencyRecord> findByShowIdAndUserIdAndIdempotencyKey(UUID showId, UUID userId, String idempotencyKey);
}
