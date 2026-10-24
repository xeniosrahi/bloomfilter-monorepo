package com.codingdevs.bloomfilter.redis.streams;

import com.codingdevs.bloomfilter.core.ScalableBloomFilter;
import com.codingdevs.bloomfilter.redis.recovery.ClusterBootstrapRecovery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.StreamEntry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Production 99.99% SLA L1/L2 Hybrid Bloom Filter Manager backed by Redis Streams
 * ({@code XADD}, {@code XREADGROUP}, {@code XACK}) with Consumer Group replay and
 * startup L1 rehydration.
 */
public class ResilientBloomManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ResilientBloomManager.class);

    private final ScalableBloomFilter localFilter;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final JedisPooled jedis;

    private final String redisFilterKey;
    private final String streamKey;
    private final String consumerGroup;
    private final String nodeId;
    private volatile boolean running = true;

    public ResilientBloomManager(
            int initialCapacity,
            double targetFalsePositiveRate,
            String redisHost,
            int redisPort) {
        this(initialCapacity, targetFalsePositiveRate, redisHost, redisPort,
                "global_bloom_l2", "bloom-stream", "bloom-cluster-group");
    }

    public ResilientBloomManager(
            int initialCapacity,
            double targetFalsePositiveRate,
            String redisHost,
            int redisPort,
            String redisFilterKey,
            String streamKey,
            String consumerGroup) {
        this.localFilter = new ScalableBloomFilter(initialCapacity, targetFalsePositiveRate);
        this.jedis = new JedisPooled(redisHost, redisPort);
        this.redisFilterKey = redisFilterKey;
        this.streamKey = streamKey;
        this.consumerGroup = consumerGroup;
        this.nodeId = "node-" + UUID.randomUUID().toString().substring(0, 8);

        // 1. Initialize central L2 RedisBloom Filter
        try {
            jedis.bfReserve(redisFilterKey, targetFalsePositiveRate, initialCapacity);
        } catch (Exception ignored) {
        }

        // 2. Initialize durable Redis Stream Consumer Group (MKSTREAM = true)
        try {
            jedis.xgroupCreate(streamKey, consumerGroup, StreamEntryID.UNRECEIVED_ENTRY, true);
        } catch (Exception ignored) {
        }

        // 3. Execute 99.99% SLA startup bootstrap recovery check
        ClusterBootstrapRecovery.hydrateL1OnStartup(
                redisHost, redisPort, streamKey, consumerGroup, redisFilterKey, localFilter);

        // 4. Start asynchronous stream consumer loop
        startStreamConsumerLoop(redisHost, redisPort);
    }

    private void startStreamConsumerLoop(String host, int port) {
        Thread streamThread = new Thread(() -> {
            try (Jedis streamJedis = new Jedis(host, port)) {
                XReadGroupParams readParams = XReadGroupParams.xReadGroupParams().block(2000).count(100);

                while (running && !Thread.currentThread().isInterrupted()) {
                    try {
                        Map<String, StreamEntryID> streamsMap = new HashMap<>();
                        streamsMap.put(streamKey, StreamEntryID.UNRECEIVED_ENTRY);

                        List<Map.Entry<String, List<StreamEntry>>> results =
                                streamJedis.xreadGroup(consumerGroup, nodeId, readParams, streamsMap);

                        if (results != null) {
                            for (Map.Entry<String, List<StreamEntry>> entry : results) {
                                for (StreamEntry streamMessage : entry.getValue()) {
                                    String item = streamMessage.getFields().get("element");
                                    if (item != null) {
                                        lock.writeLock().lock();
                                        try {
                                            if (!localFilter.mightContain(item)) {
                                                localFilter.add(item);
                                            }
                                        } finally {
                                            lock.writeLock().unlock();
                                        }
                                    }
                                    // Acknowledge processed message for Consumer Group ACK pattern
                                    streamJedis.xack(streamKey, consumerGroup, streamMessage.getID());
                                }
                            }
                        }
                    } catch (Exception e) {
                        if (running) {
                            log.warn("Stream connection fault on {}: {}", nodeId, e.getMessage());
                            try {
                                Thread.sleep(5000);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }
                }
            }
        }, "bloom-stream-consumer-" + nodeId);
        streamThread.setDaemon(true);
        streamThread.start();
    }

    /**
     * Sub-microsecond local L1 JVM heap evaluation.
     */
    public boolean mightContain(String element) {
        lock.readLock().lock();
        try {
            return localFilter.mightContain(element);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Central execution entry adding to L2 (RedisBloom), updating local L1, and appending
     * to the durable Redis Stream log for cluster broadcast.
     */
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

            Map<String, String> message = new HashMap<>();
            message.put("element", element);
            message.put("sender", nodeId);
            jedis.xadd(streamKey, StreamEntryID.NEW_ENTRY, message);
        }
    }

    public ScalableBloomFilter getLocalFilter() {
        return localFilter;
    }

    public String getNodeId() {
        return nodeId;
    }

    @Override
    public void close() {
        running = false;
        if (jedis != null) {
            jedis.close();
        }
    }
}
