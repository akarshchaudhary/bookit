package com.seatreserve.metrics;

import com.seatreserve.domain.SeatStatus;
import com.seatreserve.repo.SeatRepository;
import com.seatreserve.web.error.DeclineReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ReservationMetrics {

    private final Counter confirmed;
    private final Counter cancelled;
    private final Map<DeclineReason, Counter> declined = new EnumMap<>(DeclineReason.class);
    private final AtomicLong seatsAvailable = new AtomicLong(0);
    private final SeatRepository seatRepository;

    public ReservationMetrics(MeterRegistry registry, SeatRepository seatRepository) {
        this.seatRepository = seatRepository;
        this.confirmed = registry.counter("reservations_confirmed_total");
        this.cancelled = registry.counter("reservations_cancelled_total");
        for (DeclineReason reason : DeclineReason.values()) {
            declined.put(reason, registry.counter("reservations_declined_total", "reason", reason.name()));
        }
        registry.gauge("seats_available", Tags.empty(), seatsAvailable, AtomicLong::get);
    }

    public void markConfirmed() {
        confirmed.increment();
    }

    public void markCancelled() {
        cancelled.increment();
    }

    public void markDeclined(DeclineReason reason) {
        Counter counter = declined.get(reason);
        if (counter != null) {
            counter.increment();
        }
    }

    public void refreshAvailableGauge(java.util.UUID showId) {
        long available = seatRepository.countByShowIdAndStatus(showId, SeatStatus.available);
        seatsAvailable.set(available);
    }

    public void setAvailableGauge(long value) {
        seatsAvailable.set(value);
    }
}
