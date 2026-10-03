package com.seatreserve.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "users")
public class UserAccount {

    @Id
    private UUID id;

    @Column(name = "api_token", nullable = false, unique = true)
    private String apiToken;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    protected UserAccount() {
    }

    public UserAccount(UUID id, String apiToken, String displayName) {
        this.id = id;
        this.apiToken = apiToken;
        this.displayName = displayName;
    }

    public UUID getId() {
        return id;
    }

    public String getApiToken() {
        return apiToken;
    }

    public String getDisplayName() {
        return displayName;
    }
}
