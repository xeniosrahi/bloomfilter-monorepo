package com.codingdevs.bloomfilter.core;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Counting Bloom Filter supporting safe deletions by replacing single bits with
 * 8-bit counters ({@code byte[]}) and guarding against counter overflow ({@link Byte#MAX_VALUE}).
 *
 * @param <T> Element type stored in the filter.
 */
public class CountingBloomFilter<T> {

    private final byte[] counters;
    private final int numSlots;
    private final int numHashFunctions;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public CountingBloomFilter(int expectedElements, double falsePositiveRate) {
        FilterSizingCalculator.validateParameters(expectedElements, falsePositiveRate);
        this.numSlots = FilterSizingCalculator.optimalNumBits(expectedElements, falsePositiveRate);
        this.numHashFunctions = FilterSizingCalculator.optimalNumHashFunctions(expectedElements, numSlots);
        this.counters = new byte[numSlots];
    }

    public void add(T element) {
        if (element == null) {
            return;
        }
        int primaryHash = element.hashCode();
        lock.writeLock().lock();
        try {
            for (int i = 1; i <= numHashFunctions; i++) {
                int index = Math.floorMod(primaryHash + (i * 31), numSlots);
                if (counters[index] < Byte.MAX_VALUE) { // Prevent counter overflow
                    counters[index]++;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean mightContain(T element) {
        if (element == null) {
            return false;
        }
        int primaryHash = element.hashCode();
        lock.readLock().lock();
        try {
            for (int i = 1; i <= numHashFunctions; i++) {
                int index = Math.floorMod(primaryHash + (i * 31), numSlots);
                if (counters[index] <= 0) {
                    return false; // Zero value means definitely absent
                }
            }
            return true;
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean delete(T element) {
        if (element == null) {
            return false;
        }
        lock.writeLock().lock();
        try {
            if (!mightContain(element)) {
                return false;
            }
            int primaryHash = element.hashCode();
            for (int i = 1; i <= numHashFunctions; i++) {
                int index = Math.floorMod(primaryHash + (i * 31), numSlots);
                if (counters[index] > 0 && counters[index] < Byte.MAX_VALUE) {
                    counters[index]--;
                }
            }
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int getNumSlots() {
        return numSlots;
    }

    public int getNumHashFunctions() {
        return numHashFunctions;
    }
}
