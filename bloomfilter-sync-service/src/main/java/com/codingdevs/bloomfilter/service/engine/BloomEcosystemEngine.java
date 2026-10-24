package com.codingdevs.bloomfilter.service.engine;

import com.codingdevs.bloomfilter.core.ScalableBloomFilter;
import com.codingdevs.bloomfilter.redis.compaction.RedisStreamCompactor;
import com.codingdevs.bloomfilter.redis.recovery.ClusterBootstrapRecovery;
import com.codingdevs.bloomfilter.redis.recovery.BloomDumpChunk;
import com.codingdevs.bloomfilter.redis.recovery.RedisBloomStateTransfer;
import com.codingdevs.bloomfilter.redis.telemetry.BloomTelemetryMetrics;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.StreamEntryID;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Production L1/L2 Hybrid Bloom Ecosystem Engine:
 * - L1: In-memory JVM {@link ScalableBloomFilter} (<1 microsecond reads)
 * - L2: Centralized RedisBloom ({@code BF.RESERVE}, {@code BF.ADD}, {@code BF.EXISTS})
 * - Sync: Durable Redis Streams ({@code XADD})
 * - Zero-Downtime Rotation: Active-Passive pointer switch ({@link #rotateActiveFilter})
 * - Telemetry: Micrometer Prometheus counters and gauges
 */
@Service
public class BloomEcosystemEngine {

    private static final Logger log = LoggerFactory.getLogger(BloomEcosystemEngine.class);

    private final JedisPooled jedis;
    private final BloomTelemetryMetrics telemetry;
    private final RedisStreamCompactor streamCompactor;
    private final RedisBloomStateTransfer stateTransfer;

    private final ScalableBloomFilter localL1Filter;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final AtomicReference<String> activeKey;
    private final AtomicReference<String> passiveKey;
    private final String streamKey;
    private final String consumerGroup;
    private final int initialCapacity;
    private final double targetErrorRate;
    private final String nodeId;

    public BloomEcosystemEngine(
            JedisPooled jedis,
            BloomTelemetryMetrics telemetry,
            RedisStreamCompactor streamCompactor,
            RedisBloomStateTransfer stateTransfer,
            @Value("${bloom.initial-capacity:100000}") int initialCapacity,
            @Value("${bloom.target-error-rate:0.01}") double targetErrorRate,
            @Value("${bloom.filter-key:global_bloom_l2}") String filterKey,
            @Value("${bloom.stream-key:bloom-stream}") String streamKey,
            @Value("${bloom.consumer-group:bloom-cluster-group}") String consumerGroup) {
        this.jedis = jedis;
        this.telemetry = telemetry;
        this.streamCompactor = streamCompactor;
        this.stateTransfer = stateTransfer;
        this.initialCapacity = initialCapacity;
        this.targetErrorRate = targetErrorRate;
        this.streamKey = streamKey;
        this.consumerGroup = consumerGroup;
        this.activeKey = new AtomicReference<>(filterKey);
        this.passiveKey = new AtomicReference<>(null);
        this.localL1Filter = new ScalableBloomFilter(initialCapacity, targetErrorRate);
        this.nodeId = "node-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @PostConstruct
    public void initializeClusterState() {
        try {
            jedis.bfReserve(activeKey.get(), targetErrorRate, initialCapacity);
        } catch (Exception e) {
            log.debug("RedisBloom filter [{}] already reserved or Redis unreachable at startup: {}",
                    activeKey.get(), e.getMessage());
        }
        try {
            jedis.xgroupCreate(streamKey, consumerGroup, StreamEntryID.LAST_ENTRY, true);
        } catch (Exception ignored) {
        }
        ClusterBootstrapRecovery.hydrateL1OnStartup(
                jedis, streamKey, consumerGroup, activeKey.get(), localL1Filter);
    }

    /**
     * Checks L1 local JVM filter first, then verifies against L2 RedisBloom (active + passive during rotation).
     */
    public boolean mightContain(String element) {
        if (element == null) {
            return false;
        }
        lock.readLock().lock();
        try {
            boolean l1Hit = localL1Filter.mightContain(element);
            if (l1Hit) {
                telemetry.incrementHit();
                return true;
            }

            boolean l2Status = checkRedisBloomWithPassiveFallback(element);
            if (l2Status) {
                localL1Filter.add(element);
                telemetry.incrementHit();
            } else {
                telemetry.incrementMiss();
            }
            return l2Status;
        } finally {
            lock.readLock().unlock();
        }
    }

    private boolean checkRedisBloomWithPassiveFallback(String element) {
        try {
            if (jedis.bfExists(activeKey.get(), element)) {
                return true;
            }
            String passive = passiveKey.get();
            return passive != null && jedis.bfExists(passive, element);
        } catch (Exception e) {
            log.warn("L2 Redis check degraded ({}), relying on L1 local JVM filter.", e.getMessage());
            return false;
        }
    }

    /**
     * Adds an element to L1, L2 RedisBloom, and broadcasts to the durable Redis Stream.
     */
    public boolean add(String element) {
        if (element == null || element.isBlank()) {
            return false;
        }
        lock.writeLock().lock();
        try {
            localL1Filter.add(element);
            boolean newlyAddedInL2 = false;
            try {
                newlyAddedInL2 = jedis.bfAdd(activeKey.get(), element);
                if (newlyAddedInL2) {
                    Map<String, String> body = new HashMap<>();
                    body.put("element", element);
                    body.put("sender", nodeId);
                    jedis.xadd(streamKey, StreamEntryID.NEW_ENTRY, body);
                }
            } catch (Exception e) {
                log.warn("L2 write deferred during network degradation: {}", e.getMessage());
            }
            return newlyAddedInL2;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int addBatch(Collection<String> elements) {
        int addedCount = 0;
        if (elements == null) {
            return 0;
        }
        for (String element : elements) {
            if (add(element)) {
                addedCount++;
            }
        }
        return addedCount;
    }

    /**
     * Zero-downtime Active-Passive filter rotation.
     */
    public String rotateActiveFilter(String newKeyName, int newCapacity, double errorRate) {
        String targetKey = (newKeyName == null || newKeyName.isBlank())
                ? "bloom_" + System.currentTimeMillis()
                : newKeyName;
        lock.writeLock().lock();
        try {
            String previousActive = activeKey.get();
            jedis.bfReserve(targetKey, errorRate, newCapacity);
            passiveKey.set(previousActive);
            activeKey.set(targetKey);
            log.info("Zero-downtime rotation complete: activeKey={} | passiveKey={}", targetKey, previousActive);
            return targetKey;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<BloomDumpChunk> exportBinaryState() {
        return stateTransfer.backupBloomFilterState(activeKey.get());
    }

    public void restoreBinaryState(String targetFilterKey, List<BloomDumpChunk> chunks) {
        stateTransfer.restoreBloomFilterState(targetFilterKey, chunks);
    }

    @Scheduled(fixedDelayString = "${bloom.telemetry-refresh-ms:15000}")
    public void refreshTelemetryGauges() {
        double saturation = localL1Filter.getActiveLayerSaturation();
        long lag = streamCompactor.getMaxConsumerGroupPendingLag();
        telemetry.updateMetrics(saturation, lag);
    }

    public Map<String, Object> getClusterStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("nodeId", nodeId);
        stats.put("activeFilterKey", activeKey.get());
        stats.put("passiveFilterKey", passiveKey.get());
        stats.put("streamKey", streamKey);
        stats.put("l1LayerCount", localL1Filter.getLayerCount());
        stats.put("l1TotalInsertions", localL1Filter.getTotalInsertions());
        stats.put("l1ActiveLayerSaturation", localL1Filter.getActiveLayerSaturation());
        stats.put("streamLength", streamCompactor.getStreamLength());
        stats.put("consumerGroupPendingLag", streamCompactor.getMaxConsumerGroupPendingLag());
        stats.put("metricsHits", telemetry.getHitCount());
        stats.put("metricsMisses", telemetry.getMissCount());
        stats.put("metricsVerifiedFalsePositives", telemetry.getFalsePositiveCount());
        return stats;
    }
}
