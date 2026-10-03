package com.seatreserve.web;

import com.seatreserve.config.AuthContext;
import com.seatreserve.service.ReservationService;
import com.seatreserve.service.ShowService;
import com.seatreserve.web.dto.CreateShowRequest;
import com.seatreserve.web.dto.ReservationResponse;
import com.seatreserve.web.dto.ReserveRequest;
import com.seatreserve.web.dto.ShowResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.UUID;

@RestController
@Tag(name = "Shows", description = "Create shows, inspect state, reserve seats")
public class ShowController {

    private final ShowService showService;
    private final ReservationService reservationService;

    public ShowController(ShowService showService, ReservationService reservationService) {
        this.showService = showService;
        this.reservationService = reservationService;
    }

    @PostMapping("/shows")
    @Operation(summary = "Create show (admin)", security = @SecurityRequirement(name = "adminToken"))
    public ResponseEntity<ShowResponse> createShow(@Valid @RequestBody CreateShowRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(showService.create(request));
    }

    @GetMapping("/shows/{id}")
    @Operation(summary = "Show state + seat map (public)")
    public ShowResponse getShow(@PathVariable("id") UUID id) {
        return showService.get(id);
    }

    @PostMapping("/shows/{id}/reserve")
    @Operation(summary = "Reserve seats (user, idempotent, all-or-nothing)", security = @SecurityRequirement(name = "userToken"))
    public ResponseEntity<ReservationResponse> reserve(
            @PathVariable("id") UUID id,
            @Valid @RequestBody ReserveRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyHeader) {
        UUID userId = AuthContext.requireUserId();
        String key = idempotencyHeader != null && !idempotencyHeader.isBlank()
                ? idempotencyHeader.trim()
                : request.idempotency_key();
        ReservationService.ReserveResult result =
                reservationService.reserve(id, userId, new ReserveRequest(request.seats(), key));
        if (result.replay()) {
            return ResponseEntity.ok().header("Idempotent-Replay", "true").body(result.response());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.response());
    }
}
