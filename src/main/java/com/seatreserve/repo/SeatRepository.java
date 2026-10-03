package com.seatreserve.repo;

import com.seatreserve.domain.Seat;
import com.seatreserve.domain.SeatStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SeatRepository extends JpaRepository<Seat, UUID> {

    List<Seat> findByShowIdOrderByLabelAsc(UUID showId);

    long countByShowIdAndStatus(UUID showId, SeatStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT s FROM Seat s
            WHERE s.showId = :showId AND s.label IN :labels
            ORDER BY s.label ASC
            """)
    List<Seat> lockByShowIdAndLabels(@Param("showId") UUID showId, @Param("labels") Collection<String> labels);

    @Query("""
            SELECT COUNT(s) FROM Seat s
            JOIN Reservation r ON s.reservationId = r.id
            WHERE s.showId = :showId
              AND r.userId = :userId
              AND s.status = com.seatreserve.domain.SeatStatus.confirmed
              AND r.status = com.seatreserve.domain.ReservationStatus.confirmed
            """)
    long countConfirmedSeatsForUser(@Param("showId") UUID showId, @Param("userId") UUID userId);

    List<Seat> findByReservationIdOrderByLabelAsc(UUID reservationId);
}
