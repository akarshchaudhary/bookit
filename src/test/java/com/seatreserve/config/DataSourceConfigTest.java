package com.seatreserve.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;

import static org.assertj.core.api.Assertions.assertThat;

class DataSourceConfigTest {

    @Test
    void rewritesPostgresUrlAndExtractsCredentials() {
        DataSourceProperties props = new DataSourceProperties();
        props.setUrl("postgres://seats:secret@db.example:5432/seats");
        DataSourceConfig.normalizePostgresUrl(props);
        assertThat(props.getUrl()).isEqualTo("jdbc:postgresql://db.example:5432/seats");
        assertThat(props.getUsername()).isEqualTo("seats");
        assertThat(props.getPassword()).isEqualTo("secret");
    }
}
