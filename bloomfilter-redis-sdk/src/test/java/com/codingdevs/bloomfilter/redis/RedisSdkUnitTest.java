package com.codingdevs.bloomfilter.redis;

import com.codingdevs.bloomfilter.redis.rotation.BloomFilterRotationRunner;
import com.codingdevs.bloomfilter.redis.telemetry.BloomTelemetryMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPooled;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisSdkUnitTest {

    @Test
    void testBloomTelemetryMetricsCountersAndGauges() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BloomTelemetryMetrics metrics = new BloomTelemetryMetrics(registry);

        metrics.incrementHit();
        metrics.incrementHit();
        metrics.incrementMiss();
        metrics.recordFalsePositive();
        metrics.updateMetrics(0.74, 42L);

        assertEquals(2.0, metrics.getHitCount());
        assertEquals(1.0, metrics.getMissCount());
        assertEquals(1.0, metrics.getFalsePositiveCount());
        assertEquals(0.74, metrics.getCurrentSaturationRate(), 0.001);
        assertEquals(42L, metrics.getStreamSyncLagCount());
    }

    @Test
    void testZeroDowntimeRotationSwapsPointersAtomically() {
        JedisPooled mockJedis = mock(JedisPooled.class);
        BloomFilterRotationRunner runner = new BloomFilterRotationRunner(mockJedis, 10_000, 0.01, 50L);

        assertEquals("bloom_v1", runner.getActiveFilterKey());
        String newActive = runner.executeZeroDowntimeRotation(50_000, 0.005);

        assertTrue(newActive.startsWith("bloom_"));
        assertEquals(newActive, runner.getActiveFilterKey());
        assertEquals("bloom_v1", runner.getPassiveFilterKey());
        verify(mockJedis).bfReserve(eq(newActive), eq(0.005), eq(50_000L));
    }
}
