package com.codingdevs.bloomfilter.benchmarks.jmh;

import com.codingdevs.bloomfilter.core.CountingBloomFilter;
import com.codingdevs.bloomfilter.core.GuavaBloomFilterAdapter;
import com.codingdevs.bloomfilter.core.PartialKeyCuckooFilter;
import com.codingdevs.bloomfilter.core.ScalableBloomFilter;
import com.codingdevs.bloomfilter.core.StandardBloomFilter;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * OpenJDK JMH Microbenchmark comparing sub-microsecond L1 JVM lookup and insertion
 * throughput across Standard, Guava Murmur3, Counting, Partial-Key Cuckoo, and Scalable Bloom Filters.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(1)
public class FilterThroughputBenchmark {

    private StandardBloomFilter<String> standardFilter;
    private GuavaBloomFilterAdapter guavaFilter;
    private CountingBloomFilter<String> countingFilter;
    private PartialKeyCuckooFilter cuckooFilter;
    private ScalableBloomFilter scalableFilter;

    @Setup(Level.Trial)
    public void setUp() {
        int capacity = 100_000;
        double fpp = 0.01;

        standardFilter = new StandardBloomFilter<>(capacity, fpp);
        guavaFilter = new GuavaBloomFilterAdapter(capacity, fpp);
        countingFilter = new CountingBloomFilter<>(capacity, fpp);
        cuckooFilter = new PartialKeyCuckooFilter(32_768);
        scalableFilter = new ScalableBloomFilter(capacity, fpp);

        for (int i = 0; i < 50_000; i++) {
            String key = "active_user_key_" + i;
            standardFilter.add(key);
            guavaFilter.put(key);
            countingFilter.add(key);
            cuckooFilter.insert(key);
            scalableFilter.add(key);
        }
    }

    @Benchmark
    public void standardFilterLookupHit(Blackhole bh) {
        bh.consume(standardFilter.mightContain("active_user_key_25000"));
    }

    @Benchmark
    public void standardFilterLookupMiss(Blackhole bh) {
        bh.consume(standardFilter.mightContain("malicious_absent_key_999999"));
    }

    @Benchmark
    public void guavaFilterLookupHit(Blackhole bh) {
        bh.consume(guavaFilter.mightContain("active_user_key_25000"));
    }

    @Benchmark
    public void countingFilterLookupHit(Blackhole bh) {
        bh.consume(countingFilter.mightContain("active_user_key_25000"));
    }

    @Benchmark
    public void cuckooFilterLookupHit(Blackhole bh) {
        bh.consume(cuckooFilter.contains("active_user_key_25000"));
    }

    @Benchmark
    public void scalableFilterMultiLayerLookup(Blackhole bh) {
        bh.consume(scalableFilter.mightContain("active_user_key_25000"));
    }
}
