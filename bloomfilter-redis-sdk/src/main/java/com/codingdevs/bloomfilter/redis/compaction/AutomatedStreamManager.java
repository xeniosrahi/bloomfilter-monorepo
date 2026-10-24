package com.codingdevs.bloomfilter.redis.compaction;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Autonomous background daemon scheduler running {@link RedisStreamCompactor} every 10 minutes.
 */
public class AutomatedStreamManager implements AutoCloseable {

    private final RedisStreamCompactor compactor;
    private final ScheduledExecutorService scheduler;

    public AutomatedStreamManager(String host, int port, String streamKey, long maxWindow) {
        this(new RedisStreamCompactor(host, port, streamKey, maxWindow));
    }

    public AutomatedStreamManager(RedisStreamCompactor compactor) {
        this.compactor = compactor;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bloom-stream-compactor-thread");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void startAutoCompaction() {
        scheduler.scheduleAtFixedRate(
                compactor::runCompaction,
                1, 10, TimeUnit.MINUTES
        );
    }

    public RedisStreamCompactor getCompactor() {
        return compactor;
    }

    @Override
    public void close() {
        scheduler.shutdown();
        compactor.close();
    }
}
