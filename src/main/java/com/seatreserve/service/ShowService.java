package com.seatreserve.service;

import com.seatreserve.domain.Seat;
import com.seatreserve.domain.SeatStatus;
import com.seatreserve.domain.Show;
import com.seatreserve.metrics.ReservationMetrics;
import com.seatreserve.repo.SeatRepository;
import com.seatreserve.repo.ShowRepository;
import com.seatreserve.web.dto.CreateShowRequest;
import com.seatreserve.web.dto.ShowResponse;
import com.seatreserve.web.error.DeclineReason;
import com.seatreserve.web.error.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ShowService {

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ReservationMetrics metrics;

    public ShowService(ShowRepository showRepository, SeatRepository seatRepository, ReservationMetrics metrics) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.metrics = metrics;
    }

    @Transactional
    public ShowResponse create(CreateShowRequest request) {
        LinkedHashSet<String> uniqueSeats = new LinkedHashSet<>();
        for (String seat : request.seats()) {
            String trimmed = seat.trim();
            if (trimmed.isEmpty()) {
                throw new DomainException(DeclineReason.invalid_request, HttpStatus.BAD_REQUEST, "Empty seat label");
            }
            if (!uniqueSeats.add(trimmed)) {
                throw new DomainException(DeclineReason.invalid_request, HttpStatus.BAD_REQUEST,
                        "Duplicate seat label: " + trimmed);
            }
        }

        int perUserLimit = request.per_user_limit() == null ? 4 : request.per_user_limit();
        if (perUserLimit < 1) {
            throw new DomainException(DeclineReason.invalid_request, HttpStatus.BAD_REQUEST,
                    "per_user_limit must be >= 1");
        }

        UUID showId = UUID.randomUUID();
        Show show = new Show(showId, request.name().trim(), request.price_paise(), perUserLimit, Instant.now());
        showRepository.save(show);

        List<Seat> seats = new ArrayList<>(uniqueSeats.size());
        for (String label : uniqueSeats) {
            seats.add(new Seat(UUID.randomUUID(), showId, label, SeatStatus.available));
        }
        seatRepository.saveAll(seats);
        metrics.setAvailableGauge(seats.size());
        return toResponse(show, seats);
    }

    @Transactional(readOnly = true)
    public ShowResponse get(UUID showId) {
        Show show = showRepository.findById(showId)
                .orElseThrow(() -> new DomainException(DeclineReason.not_found, HttpStatus.NOT_FOUND, "Show not found"));
        List<Seat> seats = seatRepository.findByShowIdOrderByLabelAsc(showId);
        return toResponse(show, seats);
    }

    private ShowResponse toResponse(Show show, List<Seat> seats) {
        int available = 0;
        int held = 0;
        int confirmed = 0;
        List<Map<String, String>> seatViews = new ArrayList<>(seats.size());
        for (Seat seat : seats) {
            switch (seat.getStatus()) {
                case available -> available++;
                case held -> held++;
                case confirmed -> confirmed++;
            }
            Map<String, String> row = new LinkedHashMap<>();
            row.put("seat", seat.getLabel());
            row.put("status", seat.getStatus().name());
            seatViews.add(row);
        }
        int total = seats.size();
        if (available + held + confirmed != total) {
            throw new IllegalStateException("Seat reconciliation invariant violated for show " + show.getId());
        }
        return new ShowResponse(
                show.getId(),
                show.getName(),
                show.getPricePaise(),
                show.getPerUserLimit(),
                total,
                available,
                held,
                confirmed,
                seatViews
        );
    }
}
