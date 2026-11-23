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

    /**
     * Document generation for contracts and proposals.
     *
     * Separate from the scoring pool because the work is a different shape: each
     * task holds a docx4j render and a LibreOffice subprocess for tens of
     * seconds, where scoring tasks are short and IO-bound. Sharing one pool would
     * let a burst of contract generation starve lead scoring, and the two have no
     * reason to compete.
     *
     * Deliberately small. Every task spawns an external process, so the ceiling
     * is set by what the machine can actually run rather than by how many
     * requests arrive. CallerRunsPolicy on saturation: an overloaded server then
     * degrades to generating on the request thread — slow, and the caller may
     * time out — rather than silently discarding a contract somebody is waiting
     * for.
     */
    @Bean(name = "documentGenerationExecutor")
    public Executor documentGenerationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("doc-generation-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        // Let an in-flight render finish on shutdown. A half-written DOCX with a
        // database row pointing at it is worse than a slightly slower restart.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(180);
        executor.initialize();
        return executor;
    }
}
