package com.codingdevs.bloomfilter.redis.rotation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Zero-Downtime Automated Filter Rotation Runner using an Active-Passive Dual-Filter Pointer Switch.
 * When saturation crosses threshold (e.g., 80%), allocates a fresh oversized RedisBloom structure,
 * atomically swaps routing pointers, and cleans up the passive filter after a configurable grace window.
 */
public class BloomFilterRotationRunner {

    private static final Logger log = LoggerFactory.getLogger(BloomFilterRotationRunner.class);
    public static final long DEFAULT_PASSIVE_RETENTION_MS = 15 * 60 * 1000L; // 15 minutes

    private final JedisPooled jedis;
    private final ReentrantReadWriteLock pointerLock = new ReentrantReadWriteLock();
    private final long passiveRetentionMillis;

    private final AtomicReference<String> activeFilterKey = new AtomicReference<>("bloom_v1");
    private final AtomicReference<String> passiveFilterKey = new AtomicReference<>("bloom_v2");

    public BloomFilterRotationRunner(JedisPooled jedis, int initialCapacity, double errorRate) {
        this(jedis, initialCapacity, errorRate, DEFAULT_PASSIVE_RETENTION_MS);
    }

    public BloomFilterRotationRunner(
            JedisPooled jedis,
            int initialCapacity,
            double errorRate,
            long passiveRetentionMillis) {
        this.jedis = jedis;
        this.passiveRetentionMillis = passiveRetentionMillis;
        try {
            jedis.bfReserve(activeFilterKey.get(), errorRate, initialCapacity);
        } catch (Exception ignored) {
        }
    }

    public boolean mightContain(String element) {
        pointerLock.readLock().lock();
        try {
            return jedis.bfExists(activeFilterKey.get(), element)
                    || jedis.bfExists(passiveFilterKey.get(), element);
        } finally {
            pointerLock.readLock().unlock();
        }
    }

    public boolean add(String element) {
        pointerLock.readLock().lock();
        try {
            return jedis.bfAdd(activeFilterKey.get(), element);
        } finally {
            pointerLock.readLock().unlock();
        }
    }

    /**
     * Triggered by a Prometheus Alertmanager webhook or internal saturation monitor when saturation crosses 80%.
     * Spins up a larger structure and swaps active/passive pointers with zero downtime.
     */
    public synchronized String executeZeroDowntimeRotation(int nextScaleCapacity, double optimalErrorRate) {
        String oldActive = activeFilterKey.get();
        String nextActiveName = "bloom_" + System.currentTimeMillis();

        log.info("Allocating new oversized passive layer index: {} (capacity={}, errorRate={})",
                nextActiveName, nextScaleCapacity, optimalErrorRate);
        jedis.bfReserve(nextActiveName, optimalErrorRate, nextScaleCapacity);

        pointerLock.writeLock().lock();
        try {
            passiveFilterKey.set(oldActive);
            activeFilterKey.set(nextActiveName);
        } finally {
            pointerLock.writeLock().unlock();
        }

        log.info("Routing table pointer swapped safely. Active={} | Passive={}", nextActiveName, oldActive);

        Thread cleanupWorker = new Thread(() -> {
            try {
                Thread.sleep(passiveRetentionMillis);
                jedis.del(oldActive);
                log.info("Stale saturated partition memory wiped cleanly: {}", oldActive);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "bloom-rotation-cleanup-" + oldActive);
        cleanupWorker.setDaemon(true);
        cleanupWorker.start();

        return nextActiveName;
    }

    public String getActiveFilterKey() {
        return activeFilterKey.get();
    }

    public String getPassiveFilterKey() {
        return passiveFilterKey.get();
    }
}
