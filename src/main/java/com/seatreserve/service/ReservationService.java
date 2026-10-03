package com.seatreserve.service;

import com.seatreserve.domain.IdempotencyRecord;
import com.seatreserve.domain.Reservation;
import com.seatreserve.domain.ReservationStatus;
import com.seatreserve.domain.Seat;
import com.seatreserve.domain.SeatStatus;
import com.seatreserve.domain.Show;
import com.seatreserve.metrics.ReservationMetrics;
import com.seatreserve.repo.IdempotencyRecordRepository;
import com.seatreserve.repo.ReservationRepository;
import com.seatreserve.repo.SeatRepository;
import com.seatreserve.repo.ShowRepository;
import com.seatreserve.web.dto.ReservationResponse;
import com.seatreserve.web.dto.ReserveRequest;
import com.seatreserve.web.error.DeclineReason;
import com.seatreserve.web.error.DomainException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
public class ReservationService {

    public record ReserveResult(ReservationResponse response, boolean replay) {
    }

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
    private static final int MAX_KEY_LENGTH = 128;
    private static final int MAX_ATTEMPTS = 3;

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final ReservationMetrics metrics;
    private final TransactionTemplate tx;
    private final Semaphore bulkhead;

    @PersistenceContext
    private EntityManager entityManager;

    public ReservationService(
            ShowRepository showRepository,
            SeatRepository seatRepository,
            ReservationRepository reservationRepository,
            IdempotencyRecordRepository idempotencyRecordRepository,
            ReservationMetrics metrics,
            PlatformTransactionManager txManager,
            @Value("${app.reserve-concurrency:8}") int reserveConcurrency) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.reservationRepository = reservationRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(txManager);
        this.bulkhead = new Semaphore(reserveConcurrency, true);
    }

    /**
     * Every outcome is counted and logged exactly once, here.
     * Bulkhead bounds how many reserve transactions hold row locks at once;
     * the rest queue here in arrival order instead of piling onto Hikari.
     */
    public ReserveResult reserve(UUID showId, UUID userId, ReserveRequest request) {
        bulkhead.acquireUninterruptibly();
        try {
            return withRetry(() -> tx.execute(status -> claim(showId, userId, request)));
        } finally {
            bulkhead.release();
        }
    }

    private ReserveResult claim(UUID showId, UUID userId, ReserveRequest request) {
        String idempotencyKey = request.idempotency_key();
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH) {
            metrics.markDeclined(DeclineReason.invalid_request);
            throw new DomainException(DeclineReason.invalid_request, HttpStatus.BAD_REQUEST,
                    "idempotency_key is required (1-" + MAX_KEY_LENGTH + " characters)");
        }

        Show show = showRepository.findById(showId)
                .orElseThrow(() -> new DomainException(DeclineReason.not_found, HttpStatus.NOT_FOUND, "Show not found"));

        TreeSet<String> sortedSeats = normalizeSeats(request.seats());
        String requestHash = hashRequest(sortedSeats);

        var existing = idempotencyRecordRepository
                .findByShowIdAndUserIdAndIdempotencyKey(showId, userId, idempotencyKey);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), requestHash, userId);
        }

        List<Seat> locked = seatRepository.lockByShowIdAndLabels(showId, sortedSeats);
        if (locked.size() != sortedSeats.size()) {
            metrics.markDeclined(DeclineReason.invalid_request);
            throw new DomainException(DeclineReason.invalid_request, HttpStatus.BAD_REQUEST,
                    "One or more seats do not exist for this show");
        }

        // All-or-nothing: every requested seat must be available
        for (Seat seat : locked) {
            if (seat.getStatus() != SeatStatus.available) {
                metrics.markDeclined(DeclineReason.seat_taken);
                log.info("reserve outcome=seat_taken show={} seats={}", showId, sortedSeats);
                throw new DomainException(DeclineReason.seat_taken, HttpStatus.CONFLICT,
                        "Seat already taken: " + seat.getLabel());
            }
        }

        // Per-user limit, serialized per (show, user) so parallel requests
        // on different seats can't both pass the count check.
        lockUser(showId, userId);
        long alreadyHeld = seatRepository.countConfirmedSeatsForUser(showId, userId);
        if (alreadyHeld + sortedSeats.size() > show.getPerUserLimit()) {
            metrics.markDeclined(DeclineReason.per_user_limit);
            log.info("reserve outcome=per_user_limit show={} seats={}", showId, sortedSeats);
            throw new DomainException(DeclineReason.per_user_limit, HttpStatus.CONFLICT,
                    "Per-user seat limit exceeded (limit=" + show.getPerUserLimit() + ")");
        }

        UUID reservationId = UUID.randomUUID();
        long amount = Math.multiplyExact(show.getPricePaise(), sortedSeats.size());
        String seatLabels = String.join(",", sortedSeats);
        Reservation reservation = new Reservation(reservationId, showId, userId, amount, seatLabels, Instant.now());
        reservationRepository.save(reservation);

        for (Seat seat : locked) {
            seat.confirm(reservationId);
        }
        seatRepository.saveAll(locked);

        IdempotencyRecord record = new IdempotencyRecord(
                UUID.randomUUID(),
                showId,
                userId,
                idempotencyKey,
                requestHash,
                reservationId,
                Instant.now()
        );
        try {
            idempotencyRecordRepository.saveAndFlush(record);
        } catch (DataIntegrityViolationException ex) {
            // Concurrent first-time insert with same key — reload winner and treat as replay/conflict
            log.info("Idempotency race for show={} user={} key={}", showId, userId, idempotencyKey);
            var winner = idempotencyRecordRepository
                    .findByShowIdAndUserIdAndIdempotencyKey(showId, userId, idempotencyKey)
                    .orElseThrow(() -> new DomainException(DeclineReason.idempotent_conflict, HttpStatus.CONFLICT,
                            "Concurrent idempotency conflict; retry the original request"));
            return replayOrConflict(winner, requestHash, userId);
        }

        metrics.markConfirmed();
        metrics.refreshAvailableGauge(showId);
        log.info("reserve outcome=confirmed seats={} reservation={} show={} user={}", sortedSeats, reservationId,
                showId, userId);
        return new ReserveResult(toResponse(reservation, new ArrayList<>(sortedSeats)), false);
    }

    public ReservationResponse cancel(UUID reservationId, UUID userId) {
        Reservation cancelled = withRetry(() -> tx.execute(status -> {
            Reservation reservation = reservationRepository.findById(reservationId)
                    .orElseThrow(() -> new DomainException(DeclineReason.not_found, HttpStatus.NOT_FOUND,
                            "Reservation not found"));

            if (!reservation.getUserId().equals(userId)) {
                metrics.markDeclined(DeclineReason.not_owner);
                throw new DomainException(DeclineReason.not_owner, HttpStatus.FORBIDDEN,
                        "Only the owning user may cancel this reservation");
            }

            List<String> labels = reservation.seatLabelList();

            if (reservation.getStatus() == ReservationStatus.cancelled) {
                return reservation;
            }

            // Lock seats in label order before release
            List<Seat> locked = seatRepository.lockByShowIdAndLabels(reservation.getShowId(), labels);

            for (Seat seat : locked) {
                if (reservationId.equals(seat.getReservationId()) && seat.getStatus() == SeatStatus.confirmed) {
                    seat.release();
                }
            }
            seatRepository.saveAll(locked);
            reservation.cancel(Instant.now());
            return reservationRepository.save(reservation);
        }));

        metrics.markCancelled();
        metrics.refreshAvailableGauge(cancelled.getShowId());
        log.info("cancel outcome=cancelled reservation={} user={}", reservationId, userId);

        return new ReservationResponse(
                cancelled.getId(),
                cancelled.getShowId(),
                cancelled.getUserId(),
                cancelled.seatLabelList(),
                cancelled.getAmountPaise(),
                cancelled.getStatus().name()
        );
    }

    /** Serializes all reserve attempts of one user for one show until the transaction ends. */
    private void lockUser(UUID showId, UUID userId) {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtextextended(:k, 0))")
                .setParameter("k", showId + ":" + userId)
                .getSingleResult();
    }

    /** Deadlocks should be impossible given the lock order; this is a safety net, not a design element. */
    private <T> T withRetry(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return work.get();
            } catch (ConcurrencyFailureException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                log.warn("retrying after concurrency failure (attempt {}): {}", attempt, e.getMessage());
            }
        }
    }

    private ReserveResult replayOrConflict(IdempotencyRecord record, String requestHash, UUID userId) {
        if (!record.getRequestHash().equals(requestHash)) {
            metrics.markDeclined(DeclineReason.idempotent_conflict);
            log.info("reserve outcome=idempotent_conflict reservation={} user={}", record.getReservationId(), userId);
            throw new DomainException(DeclineReason.idempotent_conflict, HttpStatus.CONFLICT,
                    "Idempotency key reused with a different seat set");
        }
        Reservation reservation = reservationRepository.findById(record.getReservationId())
                .orElseThrow(() -> new DomainException(DeclineReason.not_found, HttpStatus.NOT_FOUND,
                        "Stored reservation missing"));
        metrics.markDeclined(DeclineReason.idempotent_replay);
        log.info("reserve outcome=idempotent_replay reservation={} user={}", reservation.getId(), userId);
        return new ReserveResult(toResponse(reservation, reservation.seatLabelList()), true);
    }

    private TreeSet<String> normalizeSeats(List<String> seats) {
        TreeSet<String> sorted = new TreeSet<>();
        if (seats == null) {
            throw new DomainException(DeclineReason.invalid_request, HttpStatus.BAD_REQUEST, "No seats requested");
        }
        for (String seat : seats) {
            if (seat == null || seat.isBlank()) {
                throw new DomainException(DeclineReason.invalid_request, HttpStatus.BAD_REQUEST, "Empty seat label");
            }
            sorted.add(seat.trim());
        }
        if (sorted.isEmpty()) {
            throw new DomainException(DeclineReason.invalid_request, HttpStatus.BAD_REQUEST, "No seats requested");
        }
        return sorted;
    }

    private String hashRequest(TreeSet<String> seats) {
        String canonical = seats.stream().collect(Collectors.joining(","));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private ReservationResponse toResponse(Reservation reservation, List<String> seats) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getShowId(),
                reservation.getUserId(),
                seats,
                reservation.getAmountPaise(),
                reservation.getStatus().name()
        );
    }
}
