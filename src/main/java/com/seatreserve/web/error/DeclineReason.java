package com.seatreserve.web.error;

public enum DeclineReason {
    seat_taken,
    per_user_limit,
    idempotent_conflict,
    idempotent_replay,
    not_owner,
    invalid_request,
    not_found
}
