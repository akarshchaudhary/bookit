package com.seatreserve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Tag("concurrency")
class ReservationConcurrencyIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seats")
            .withUsername("seats")
            .withPassword("seats");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.admin-token", () -> "test-admin-token");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void hotSeatStorm_exactlyOneWinner() throws Exception {
        String showId = createShow(List.of("A1", "A2", "A3", "A4", "A5"), 4);

        int threads = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger serverError = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 1; i <= threads; i++) {
            String token = String.format("user-token-%03d", i);
            String key = "hot-" + i;
            futures.add(pool.submit(() -> {
                try {
                    start.await(30, TimeUnit.SECONDS);
                    MvcResult result = mockMvc.perform(post("/shows/" + showId + "/reserve")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            {"seats":["A1"],"idempotency_key":"%s"}
                                            """.formatted(key)))
                            .andReturn();
                    int status = result.getResponse().getStatus();
                    if (status == 201) {
                        created.incrementAndGet();
                    } else if (status == 409) {
                        conflict.incrementAndGet();
                    } else if (status >= 500) {
                        serverError.incrementAndGet();
                    }
                } catch (Exception e) {
                    serverError.incrementAndGet();
                }
            }));
        }

        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(serverError.get()).isZero();
        assertThat(created.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(threads - 1);
        assertInvariant(showId);
    }

    @Test
    void perUserLimit_holdsUnderConcurrency() throws Exception {
        String showId = createShow(List.of("B1", "B2", "B3", "B4", "B5", "B6", "B7", "B8", "B9", "B10"), 4);
        String token = "user-token-010";

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            String seat = "B" + (i + 1);
            String key = "limit-" + i;
            futures.add(pool.submit(() -> {
                try {
                    start.await(30, TimeUnit.SECONDS);
                    MvcResult result = mockMvc.perform(post("/shows/" + showId + "/reserve")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            {"seats":["%s"],"idempotency_key":"%s"}
                                            """.formatted(seat, key)))
                            .andReturn();
                    if (result.getResponse().getStatus() == 201) {
                        created.incrementAndGet();
                    }
                } catch (Exception ignored) {
                }
            }));
        }

        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(created.get()).isLessThanOrEqualTo(4);
        assertInvariant(showId);
    }

    @Test
    void idempotency_replayAndConflict() throws Exception {
        String showId = createShow(List.of("C1", "C2"), 4);
        String token = "user-token-020";

        MvcResult first = mockMvc.perform(post("/shows/" + showId + "/reserve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"seats":["C1"],"idempotency_key":"same-key"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String reservationId = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("reservation_id").asText();

        MvcResult replay = mockMvc.perform(post("/shows/" + showId + "/reserve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"seats":["C1"],"idempotency_key":"same-key"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(replay.getResponse().getContentAsString())
                .get("reservation_id").asText()).isEqualTo(reservationId);

        mockMvc.perform(post("/shows/" + showId + "/reserve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"seats":["C2"],"idempotency_key":"same-key"}
                                """))
                .andExpect(status().isConflict());
    }

    private String createShow(List<String> seats, int limit) throws Exception {
        String seatsJson = objectMapper.writeValueAsString(seats);
        MvcResult result = mockMvc.perform(post("/shows")
                        .header("X-Admin-Token", "test-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"t-%s","seats":%s,"price_paise":25000,"per_user_limit":%d}
                                """.formatted(UUID.randomUUID(), seatsJson, limit)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void assertInvariant(String showId) throws Exception {
        MvcResult result = mockMvc.perform(get("/shows/" + showId)).andExpect(status().isOk()).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        int total = body.get("total_seats").asInt();
        int available = body.get("available").asInt();
        int held = body.get("held").asInt();
        int confirmed = body.get("confirmed").asInt();
        assertThat(available + held + confirmed).isEqualTo(total);
    }
}
