package com.codingdevs.bloomfilter.service.engine;

import com.codingdevs.bloomfilter.redis.telemetry.BloomTelemetryMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPooled;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implements the 4-Stage Cache Penetration Prevention Architecture:
 * 1. Incoming Request -> Check L1/L2 Bloom Filter first.
 *    - If 0 (Definitely Absent): Immediately return empty (HTTP 404) without touching Redis KV cache or Database.
 *    - If 1 (Possibly Present): Proceed to Step 2.
 * 2. Check standard Redis Key-Value Cache.
 *    - Cache Hit: Return cached payload immediately.
 *    - Cache Miss: Proceed to Step 3.
 * 3. Query Primary Database.
 *    - Found: Populate Redis KV cache and return data.
 *    - Not Found (False Positive case): Increment {@code bloom.filter.false.positives} counter and return 404.
 */
@Service
public class CachePenetrationGuardService {

    private static final Logger log = LoggerFactory.getLogger(CachePenetrationGuardService.class);
    private static final String USER_CACHE_PREFIX = "cache:user:";

    private final BloomEcosystemEngine bloomEngine;
    private final JedisPooled jedis;
    private final BloomTelemetryMetrics telemetry;

    // Simulated primary backing store for demonstration and integration testing
    private final Map<String, String> primaryDatabase = new ConcurrentHashMap<>();

    public CachePenetrationGuardService(
            BloomEcosystemEngine bloomEngine,
            JedisPooled jedis,
            BloomTelemetryMetrics telemetry) {
        this.bloomEngine = bloomEngine;
        this.jedis = jedis;
        this.telemetry = telemetry;
    }

    public void registerRecord(String id, String payload) {
        primaryDatabase.put(id, payload);
        bloomEngine.add(id);
        try {
            jedis.setex(USER_CACHE_PREFIX + id, 3600, payload);
        } catch (Exception e) {
            log.debug("Redis KV cache write skipped: {}", e.getMessage());
        }
    }

    public Optional<LookupResult> getProtectedResource(String id) {
        // Step 1: L1/L2 Bloom Filter Gate
        if (!bloomEngine.mightContain(id)) {
            return Optional.empty(); // Fast 404 without touching Cache or DB
        }

        // Step 2: Check standard Redis Key-Value Cache
        try {
            String cached = jedis.get(USER_CACHE_PREFIX + id);
            if (cached != null) {
                return Optional.of(new LookupResult(id, cached, "REDIS_KV_CACHE_HIT"));
            }
        } catch (Exception e) {
            log.debug("Redis KV cache read skipped: {}", e.getMessage());
        }

        // Step 3: Query Primary Database
        String dbRecord = primaryDatabase.get(id);
        if (dbRecord != null) {
            try {
                jedis.setex(USER_CACHE_PREFIX + id, 3600, dbRecord);
            } catch (Exception ignored) {
            }
            return Optional.of(new LookupResult(id, dbRecord, "PRIMARY_DATABASE_HIT"));
        }

        // Step 4: Verified False Positive — Bloom Filter returned true, but DB returned 404
        log.warn("Verified Bloom Filter False Positive detected for key [{}]. Recording telemetry.", id);
        telemetry.recordFalsePositive();
        return Optional.empty();
    }

    public record LookupResult(String id, String payload, String source) {
    }
}
