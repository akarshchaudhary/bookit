package com.seatreserve.web.error;

import org.springframework.http.HttpStatus;

public class DomainException extends RuntimeException {

    private final DeclineReason reason;
    private final HttpStatus status;

    public DomainException(DeclineReason reason, HttpStatus status, String message) {
        super(message);
        this.reason = reason;
        this.status = status;
    }

    public DeclineReason getReason() {
        return reason;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
