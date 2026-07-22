package com.techcrm.crm.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Bean(name = "leadScoringExecutor")
    public Executor leadScoringExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // Small pool: the AI model server serializes inference behind a
        // single lock regardless (see serve.py), so extra threads here
        // don't add throughput. This just keeps CSV-import scoring off the
        // HTTP request-handling threads and stops one slow row from
        // blocking the next request.
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("lead-scoring-");
        executor.initialize();
        return executor;
    }
}
