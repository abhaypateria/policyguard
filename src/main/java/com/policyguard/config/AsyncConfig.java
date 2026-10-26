package com.policyguard.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Ingestion (parse -> chunk -> embed -> store) is slow, so it runs on a background thread.
 * The upload request returns immediately with status PROCESSING; the UI polls until INDEXED.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "indexingExecutor")
    public ThreadPoolTaskExecutor indexingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);   // one document at a time keeps memory use predictable
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("indexer-");
        executor.initialize();
        return executor;
    }
}
