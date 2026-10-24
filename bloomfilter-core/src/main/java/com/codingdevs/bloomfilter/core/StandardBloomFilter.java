package com.codingdevs.bloomfilter.core;

import java.util.BitSet;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe standard Bloom Filter backed by {@link java.util.BitSet}.
 * Computes k indices using optimal sizing formulas:
 *   m = - (n * ln(p)) / (ln(2))^2
 *   k = (m / n) * ln(2)
 *
 * @param <T> Element type stored in the filter.
 */
public class StandardBloomFilter<T> {

    private final BitSet bitArray;
    private final int numBits;          // m
    private final int numHashFunctions; // k
    private final int expectedElements; // n
    private final double falsePositiveRate;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public StandardBloomFilter(int expectedElements, double falsePositiveRate) {
        FilterSizingCalculator.validateParameters(expectedElements, falsePositiveRate);
        this.expectedElements = expectedElements;
        this.falsePositiveRate = falsePositiveRate;
        this.numBits = FilterSizingCalculator.optimalNumBits(expectedElements, falsePositiveRate);
        this.numHashFunctions = FilterSizingCalculator.optimalNumHashFunctions(expectedElements, numBits);
        this.bitArray = new BitSet(numBits);
    }

    public void add(T element) {
        if (element == null) {
            return;
        }
        int primaryHash = element.hashCode();
        int secondaryHash = MurmurHash3Util.hash32(String.valueOf(element), 0x9747b28c);

        lock.writeLock().lock();
        try {
            for (int i = 1; i <= numHashFunctions; i++) {
                int combinedHash = primaryHash + (i * 31) ^ (secondaryHash * i);
                int bitIndex = Math.floorMod(combinedHash, numBits);
                bitArray.set(bitIndex);
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
        int secondaryHash = MurmurHash3Util.hash32(String.valueOf(element), 0x9747b28c);

        lock.readLock().lock();
        try {
            for (int i = 1; i <= numHashFunctions; i++) {
                int combinedHash = primaryHash + (i * 31) ^ (secondaryHash * i);
                int bitIndex = Math.floorMod(combinedHash, numBits);
                if (!bitArray.get(bitIndex)) {
                    return false; // Definitely not present
                }
            }
            return true; // Might be present
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the ratio of bits flipped to 1 (0.0 to 1.0).
     */
    public double getSaturationRatio() {
        lock.readLock().lock();
        try {
            return (double) bitArray.cardinality() / (double) numBits;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int getNumBits() {
        return numBits;
    }

    public int getNumHashFunctions() {
        return numHashFunctions;
    }

    public int getExpectedElements() {
        return expectedElements;
    }

    public double getFalsePositiveRate() {
        return falsePositiveRate;
    }
}
