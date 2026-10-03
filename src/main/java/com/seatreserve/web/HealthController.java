package com.seatreserve.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;

@RestController
@Tag(name = "Health", description = "Liveness / readiness")
public class HealthController {

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/healthz")
    @Operation(summary = "Liveness (no auth)")
    public Map<String, String> liveness() {
        return Map.of("status", "UP");
    }

    @GetMapping("/readyz")
    @Operation(summary = "Readiness, checks DB (no auth, fails closed)")
    public ResponseEntity<Map<String, String>> readiness() {
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.isValid(2)) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("status", "DOWN", "reason", "db_invalid"));
            }
            return ResponseEntity.ok(Map.of("status", "UP"));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "DOWN", "reason", "db_unreachable"));
        }
    }
}
