package com.techcrm.crm.marketing.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class MarketingConfig {

    /**
     * Runs the two AI pipelines. Kept apart from leadScoringExecutor so a
     * campaign scoring hundreds of leads never queues behind, or ahead of, the
     * CRM's own lead scoring.
     *
     * Two threads is the point, not a tuning guess: lead evaluation and
     * content generation for one campaign start together, and with a single
     * thread the content pipeline would wait for every lead to finish. Beyond
     * two there is nothing to gain; the model server serialises inference.
     */
    @Bean(name = "marketingAiExecutor")
    public Executor marketingAiExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("marketing-ai-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
