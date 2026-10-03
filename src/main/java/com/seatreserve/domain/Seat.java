package com.seatreserve.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "seats")
public class Seat {

    @Id
    private UUID id;

    @Column(name = "show_id", nullable = false)
    private UUID showId;

    @Column(nullable = false)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeatStatus status;

    @Column(name = "reservation_id")
    private UUID reservationId;

    protected Seat() {
    }

    public Seat(UUID id, UUID showId, String label, SeatStatus status) {
        this.id = id;
        this.showId = showId;
        this.label = label;
        this.status = status;
    }

    public UUID getId() {
        return id;
    }

    public UUID getShowId() {
        return showId;
    }

    public String getLabel() {
        return label;
    }

    public SeatStatus getStatus() {
        return status;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public void confirm(UUID reservationId) {
        this.status = SeatStatus.confirmed;
        this.reservationId = reservationId;
    }

    public void release() {
        this.status = SeatStatus.available;
        this.reservationId = null;
    }
}
