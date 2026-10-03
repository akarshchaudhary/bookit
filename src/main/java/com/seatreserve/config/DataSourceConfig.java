package com.seatreserve.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.net.URI;

@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource dataSource(DataSourceProperties properties) {
        normalizePostgresUrl(properties);
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    static void normalizePostgresUrl(DataSourceProperties properties) {
        String url = properties.getUrl();
        if (url == null) {
            return;
        }
        if (!(url.startsWith("postgres://") || url.startsWith("postgresql://"))) {
            return;
        }
        URI uri = URI.create(url);
        String userInfo = uri.getUserInfo();
        if (userInfo != null && userInfo.contains(":")) {
            String[] parts = userInfo.split(":", 2);
            if (properties.getUsername() == null) {
                properties.setUsername(parts[0]);
            }
            if (properties.getPassword() == null) {
                properties.setPassword(parts[1]);
            }
        }
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String path = uri.getPath() == null ? "" : uri.getPath();
        properties.setUrl("jdbc:postgresql://" + uri.getHost() + ":" + port + path);
    }
}
