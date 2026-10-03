package com.seatreserve.web;

import com.seatreserve.config.AuthContext;
import com.seatreserve.service.ReservationService;
import com.seatreserve.web.dto.ReservationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@Tag(name = "Reservations", description = "Cancel own reservations")
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping("/reservations/{id}/cancel")
    @Operation(summary = "Cancel own reservation (user)", security = @SecurityRequirement(name = "userToken"))
    public ReservationResponse cancel(@PathVariable("id") UUID id) {
        return reservationService.cancel(id, AuthContext.requireUserId());
    }
}
