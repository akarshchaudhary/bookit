package com.seatreserve.web.dto;

public record ErrorResponse(String error, String message, String reason) {
}
