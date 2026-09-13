package com.policyguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Application entry point.
 *
 * @SpringBootApplication does three things:
 *   1. Marks this as a Spring Boot app.
 *   2. Turns on auto-configuration (Spring sets up Tomcat, JPA, etc. from the jars on the classpath).
 *   3. Scans this package (com.policyguard) and everything under it for our components
 *      (controllers, services, repositories) and wires them together.
 */
@SpringBootApplication
public class PolicyGuardApplication {

    public static void main(String[] args) {
        // Boots Spring, starts the embedded Tomcat web server on port 8081.
        SpringApplication.run(PolicyGuardApplication.class, args);
    }
}
