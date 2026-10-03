package com.seatreserve.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "reservations")
public class Reservation {

    @Id
    private UUID id;

    @Column(name = "show_id", nullable = false)
    private UUID showId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Column(name = "seat_labels", nullable = false)
    private String seatLabels;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    protected Reservation() {
    }

    public Reservation(UUID id, UUID showId, UUID userId, long amountPaise, String seatLabels, Instant createdAt) {
        this.id = id;
        this.showId = showId;
        this.userId = userId;
        this.amountPaise = amountPaise;
        this.seatLabels = seatLabels;
        this.status = ReservationStatus.confirmed;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getShowId() {
        return showId;
    }

    public UUID getUserId() {
        return userId;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public List<String> seatLabelList() {
        if (seatLabels == null || seatLabels.isBlank()) {
            return List.of();
        }
        return Arrays.asList(seatLabels.split(","));
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public void cancel(Instant at) {
        this.status = ReservationStatus.cancelled;
        this.cancelledAt = at;
    }
}
