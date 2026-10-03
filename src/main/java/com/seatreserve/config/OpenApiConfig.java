package com.seatreserve.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI seatReservationApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Seat Reservation API")
                        .version("1.0.0")
                        .description("Atomic seat reservation: sorted FOR UPDATE locks, all-or-nothing multi-seat, idempotency keys, per-user limit 4."))
                .components(new Components()
                        .addSecuritySchemes("adminToken", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Admin-Token")
                                .description("Admin token, default: admin-secret-token"))
                        .addSecuritySchemes("userToken", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("opaque")
                                .description("User token: user-token-001 .. user-token-500")))
                .addSecurityItem(new SecurityRequirement().addList("userToken"));
    }
}
