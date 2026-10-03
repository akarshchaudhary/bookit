package com.seatreserve.web.dto;

import java.util.List;
import java.util.UUID;

public record ReservationResponse(
        UUID reservation_id,
        UUID show_id,
        UUID user_id,
        List<String> seats,
        long amount_paise,
        String status
) {
}
