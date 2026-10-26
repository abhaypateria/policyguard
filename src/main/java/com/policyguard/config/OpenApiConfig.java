package com.policyguard.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Interactive API documentation at /swagger-ui.html */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI policyGuardOpenApi() {
        return new OpenAPI().info(new Info()
                .title("PolicyGuard API")
                .version("1.0.0")
                .description("Temporally-grounded, self-correcting RAG for banking compliance: "
                        + "cited answers, calibrated refusal, version-aware retrieval and a tamper-evident audit log."));
    }
}
