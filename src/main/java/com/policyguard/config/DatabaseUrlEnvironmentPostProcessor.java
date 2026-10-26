package com.policyguard.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Neon (and most cloud hosts) hand you ONE connection string:
 *     postgresql://user:password@host/dbname?sslmode=require
 * JDBC needs it split into url + username + password. This runs before the app starts and
 * does that split, so only the single string ever needs to be pasted into DATABASE_URL.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        String explicitJdbc = env.getProperty("DB_URL");
        String databaseUrl = env.getProperty("DATABASE_URL");
        if ((explicitJdbc != null && !explicitJdbc.isBlank()) || databaseUrl == null || databaseUrl.isBlank()) {
            return;
        }
        URI uri = URI.create(databaseUrl.trim().replaceFirst("^postgres(ql)?://", "http://"));
        String[] userInfo = uri.getRawUserInfo().split(":", 2);
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();

        // Keep only parameters the PostgreSQL JDBC driver understands; always require TLS.
        List<String> params = new ArrayList<>();
        if (uri.getRawQuery() != null) {
            for (String p : uri.getRawQuery().split("&")) {
                if (p.startsWith("sslmode=")) params.add(p);
            }
        }
        if (params.isEmpty()) params.add("sslmode=require");
        // Serverless Postgres can take a few seconds to wake up; the driver's 10 s default is too tight.
        params.add("connectTimeout=30");
        params.add("tcpKeepAlive=true");

        String jdbc = "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getRawPath() + "?" + String.join("&", params);
        env.getPropertySources().addFirst(new MapPropertySource("policyguardDatabaseUrl", Map.of(
                "DB_URL", jdbc,
                "DB_USERNAME", URLDecoder.decode(userInfo[0], StandardCharsets.UTF_8),
                "DB_PASSWORD", userInfo.length > 1 ? URLDecoder.decode(userInfo[1], StandardCharsets.UTF_8) : "")));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE; // run after the .env file has been loaded
    }
}
