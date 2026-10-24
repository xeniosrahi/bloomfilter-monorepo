package com.codingdevs.bloomfilter.service;

import com.codingdevs.bloomfilter.redis.compaction.RedisStreamCompactor;
import com.codingdevs.bloomfilter.redis.recovery.RedisBloomStateTransfer;
import com.codingdevs.bloomfilter.redis.telemetry.BloomTelemetryMetrics;
import com.codingdevs.bloomfilter.service.engine.BloomEcosystemEngine;
import com.codingdevs.bloomfilter.service.engine.CachePenetrationGuardService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPooled;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class BloomEcosystemEngineTest {

    private JedisPooled mockJedis;
    private BloomTelemetryMetrics telemetry;
    private BloomEcosystemEngine engine;
    private CachePenetrationGuardService guardService;

    @BeforeEach
    void setUp() {
        mockJedis = mock(JedisPooled.class);
        telemetry = new BloomTelemetryMetrics(new SimpleMeterRegistry());
        RedisStreamCompactor compactor = new RedisStreamCompactor(mockJedis, "bloom-stream", 5000L);
        RedisBloomStateTransfer stateTransfer = new RedisBloomStateTransfer(mockJedis, "global_bloom_l2");

        engine = new BloomEcosystemEngine(
                mockJedis, telemetry, compactor, stateTransfer,
                10_000, 0.01, "global_bloom_l2", "bloom-stream", "bloom-cluster-group");
        guardService = new CachePenetrationGuardService(engine, mockJedis, telemetry);
    }

    @Test
    void testCachePenetrationShieldBlocksNonExistentIdAtFilterLayer() {
        when(mockJedis.bfExists(anyString(), eq("malicious_random_id_999"))).thenReturn(false);

        Optional<CachePenetrationGuardService.LookupResult> result =
                guardService.getProtectedResource("malicious_random_id_999");

        assertTrue(result.isEmpty(), "Expected fast 404 when L1 and L2 report absent");
        assertEquals(1.0, telemetry.getMissCount());
        verify(mockJedis, never()).get("cache:user:malicious_random_id_999");
    }

    @Test
    void testRegisteredUserReturnsFromCacheOrDatabase() {
        when(mockJedis.bfAdd(anyString(), eq("user_42"))).thenReturn(true);
        guardService.registerRecord("user_42", "{\"name\":\"Ada Lovelace\"}");

        Optional<CachePenetrationGuardService.LookupResult> result =
                guardService.getProtectedResource("user_42");

        assertTrue(result.isPresent());
        assertEquals("user_42", result.get().id());
        assertEquals(1.0, telemetry.getHitCount());
    }
}
