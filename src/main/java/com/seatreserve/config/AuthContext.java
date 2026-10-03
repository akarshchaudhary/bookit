package com.seatreserve.config;

import java.util.UUID;

public final class AuthContext {

    private static final ThreadLocal<UUID> CURRENT_USER = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void setUserId(UUID userId) {
        CURRENT_USER.set(userId);
    }

    public static UUID requireUserId() {
        UUID userId = CURRENT_USER.get();
        if (userId == null) {
            throw new IllegalStateException("No authenticated user on request");
        }
        return userId;
    }

    public static void clear() {
        CURRENT_USER.remove();
    }
}
