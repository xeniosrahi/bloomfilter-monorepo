package com.codingdevs.bloomfilter.redis.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Micrometer telemetry instrumentation exposing Prometheus metrics for:
 * - {@code bloom.filter.queries} (tagged with {@code result=hit} and {@code result=miss})
 * - {@code bloom.filter.false.positives} (verified 404 from primary database after Bloom hit)
 * - {@code bloom.filter.saturation} (percentage of bits flipped to 1 in primary layer)
 * - {@code bloom.stream.sync.lag} (unread Redis Stream messages backlog)
 */
public class BloomTelemetryMetrics {

    private final Counter cacheHits;
    private final Counter cacheMisses;
    private final Counter verifiedFalsePositives;

    private volatile double currentSaturationRate = 0.0;
    private final AtomicLong streamSyncLagCount = new AtomicLong(0L);

    public BloomTelemetryMetrics(MeterRegistry registry) {
        this.cacheHits = Counter.builder("bloom.filter.queries")
                .tag("result", "hit")
                .description("Number of items suspected to be present in L1/L2")
                .register(registry);

        this.cacheMisses = Counter.builder("bloom.filter.queries")
                .tag("result", "miss")
                .description("Number of items definitely absent")
                .register(registry);

        this.verifiedFalsePositives = Counter.builder("bloom.filter.false.positives")
                .description("Number of times L1/L2 reported true but Database returned 404")
                .register(registry);

        Gauge.builder("bloom.filter.saturation", () -> currentSaturationRate)
                .description("Percentage of bits flipped to 1 in the primary layer")
                .register(registry);

        Gauge.builder("bloom.stream.sync.lag", streamSyncLagCount::get)
                .description("Number of unread sync stream messages backlog")
                .register(registry);
    }

    public void incrementHit() {
        cacheHits.increment();
    }

    public void incrementMiss() {
        cacheMisses.increment();
    }

    public void recordFalsePositive() {
        verifiedFalsePositives.increment();
    }

    public void updateMetrics(double saturation, long lag) {
        this.currentSaturationRate = saturation;
        this.streamSyncLagCount.set(lag);
    }

    public double getHitCount() {
        return cacheHits.count();
    }

    public double getMissCount() {
        return cacheMisses.count();
    }

    public double getFalsePositiveCount() {
        return verifiedFalsePositives.count();
    }

    public double getCurrentSaturationRate() {
        return currentSaturationRate;
    }

    public long getStreamSyncLagCount() {
        return streamSyncLagCount.get();
    }
}
