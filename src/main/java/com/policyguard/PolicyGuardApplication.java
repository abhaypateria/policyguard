package com.policyguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Application entry point.
 *
 * @SpringBootApplication = component scanning (finds our controllers/services/repositories)
 *                        + auto-configuration (Tomcat, JPA, Flyway, Spring AI...) from the classpath.
 * @ConfigurationPropertiesScan binds the typed "policyguard.*" settings (PolicyGuardProperties).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PolicyGuardApplication {

    public static void main(String[] args) {
        SpringApplication.run(PolicyGuardApplication.class, args);
    }
}
