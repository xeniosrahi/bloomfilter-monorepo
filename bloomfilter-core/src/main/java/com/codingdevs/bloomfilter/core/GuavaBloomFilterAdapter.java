package com.codingdevs.bloomfilter.core;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Production-grade Thread-Safe wrapper around Google Guava's Murmur3-backed {@link BloomFilter}.
 */
public class GuavaBloomFilterAdapter {

    private final BloomFilter<CharSequence> filter;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public GuavaBloomFilterAdapter(int expectedInsertions, double falsePositiveRate) {
        FilterSizingCalculator.validateParameters(expectedInsertions, falsePositiveRate);
        this.filter = BloomFilter.create(
                Funnels.stringFunnel(StandardCharsets.UTF_8),
                expectedInsertions,
                falsePositiveRate
        );
    }

    private GuavaBloomFilterAdapter(BloomFilter<CharSequence> filter) {
        this.filter = filter;
    }

    public boolean put(String element) {
        if (element == null) {
            return false;
        }
        lock.writeLock().lock();
        try {
            return filter.put(element);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean mightContain(String element) {
        if (element == null) {
            return false;
        }
        lock.readLock().lock();
        try {
            return filter.mightContain(element);
        } finally {
            lock.readLock().unlock();
        }
    }

    public double expectedFpp() {
        lock.readLock().lock();
        try {
            return filter.expectedFpp();
        } finally {
            lock.readLock().unlock();
        }
    }

    public long approximateElementCount() {
        lock.readLock().lock();
        try {
            return filter.approximateElementCount();
        } finally {
            lock.readLock().unlock();
        }
    }

    public void writeTo(OutputStream out) throws IOException {
        lock.readLock().lock();
        try {
            filter.writeTo(out);
        } finally {
            lock.readLock().unlock();
        }
    }

    public static GuavaBloomFilterAdapter readFrom(InputStream in) throws IOException {
        BloomFilter<CharSequence> restored = BloomFilter.readFrom(
                in, Funnels.stringFunnel(StandardCharsets.UTF_8));
        return new GuavaBloomFilterAdapter(restored);
    }
}
