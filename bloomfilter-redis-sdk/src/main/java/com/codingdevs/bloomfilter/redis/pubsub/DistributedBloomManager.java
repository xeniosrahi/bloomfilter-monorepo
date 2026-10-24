package com.codingdevs.bloomfilter.redis.pubsub;

import com.codingdevs.bloomfilter.core.ScalableBloomFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.JedisPubSub;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe L1 (local JVM {@link ScalableBloomFilter}) + L2 (RedisBloom) manager
 * using Redis Pub/Sub channel {@code bloom-sync} for real-time cluster broadcast.
 */
public class DistributedBloomManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DistributedBloomManager.class);

    private final ScalableBloomFilter localFilter;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final JedisPooled jedis;
    private final String redisFilterKey;
    private final String channelName;
    private volatile boolean running = true;

    public DistributedBloomManager(
            int initialCapacity,
            double targetFalsePositiveRate,
            String redisHost,
            int redisPort) {
        this(initialCapacity, targetFalsePositiveRate, redisHost, redisPort, "global_bloom_l2", "bloom-sync");
    }

    public DistributedBloomManager(
            int initialCapacity,
            double targetFalsePositiveRate,
            String redisHost,
            int redisPort,
            String redisFilterKey,
            String channelName) {
        this.localFilter = new ScalableBloomFilter(initialCapacity, targetFalsePositiveRate);
        this.jedis = new JedisPooled(redisHost, redisPort);
        this.redisFilterKey = redisFilterKey;
        this.channelName = channelName;

        try {
            jedis.bfReserve(redisFilterKey, targetFalsePositiveRate, initialCapacity);
        } catch (Exception ignored) {
            // Filter already exists in Redis Stack; safe to ignore
        }

        startSyncListener(redisHost, redisPort);
    }

    private void startSyncListener(String host, int port) {
        Thread listenerThread = new Thread(() -> {
            while (running && !Thread.currentThread().isInterrupted()) {
                try (Jedis subJedis = new Jedis(host, port)) {
                    subJedis.subscribe(new JedisPubSub() {
                        @Override
                        public void onMessage(String channel, String message) {
                            lock.writeLock().lock();
                            try {
                                if (!localFilter.mightContain(message)) {
                                    localFilter.add(message);
                                }
                            } finally {
                                lock.writeLock().unlock();
                            }
                        }
                    }, channelName);
                } catch (Exception e) {
                    if (running) {
                        log.warn("Redis Pub/Sub Sync Connection Lost: {}. Retrying in 2s...", e.getMessage());
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        }, "bloom-pubsub-listener");
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    public boolean mightContain(String element) {
        lock.readLock().lock();
        try {
            return localFilter.mightContain(element);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void add(String element) {
        if (element == null) {
            return;
        }
        boolean isNew = jedis.bfAdd(redisFilterKey, element);
        if (isNew) {
            lock.writeLock().lock();
            try {
                localFilter.add(element);
            } finally {
                lock.writeLock().unlock();
            }
            jedis.publish(channelName, element);
        }
    }

    @Override
    public void close() {
        running = false;
        if (jedis != null) {
            jedis.close();
        }
    }
}
