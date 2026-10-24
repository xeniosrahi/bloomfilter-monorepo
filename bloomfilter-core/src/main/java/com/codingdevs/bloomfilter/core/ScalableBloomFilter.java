package com.codingdevs.bloomfilter.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Dynamically scaling, thread-safe Bloom Filter with dense binary byte-stream
 * serialization ({@link DataOutputStream} / {@link DataInputStream}).
 *
 * <p>When the active top layer reaches 80% capacity, a new {@link BloomFilterLayer}
 * is allocated with 2x capacity and a geometrically tightened false positive rate
 * ({@code tighteningRatio = 0.8}) to preserve the overall target error budget.
 */
public class ScalableBloomFilter {

    public static final double DEFAULT_TIGHTENING_RATIO = 0.8;
    public static final double LAYER_FULL_THRESHOLD = 0.8;

    /**
     * Represents an individual standard Bloom Filter layer inside the scalable chain.
     */
    public static class BloomFilterLayer {
        private final BitSet bitArray;
        private final int numBits;          // m
        private final int numHashFunctions; // k
        private final int maxCapacity;
        private int currentInsertionCount;

        public BloomFilterLayer(int capacity, double falsePositiveRate) {
            this.maxCapacity = capacity;
            this.currentInsertionCount = 0;
            this.numBits = FilterSizingCalculator.optimalNumBits(capacity, falsePositiveRate);
            this.numHashFunctions = FilterSizingCalculator.optimalNumHashFunctions(capacity, numBits);
            this.bitArray = new BitSet(numBits);
        }

        BloomFilterLayer(int maxCapacity, int currentInsertionCount, int numBits, int numHashFunctions, BitSet bitArray) {
            this.maxCapacity = maxCapacity;
            this.currentInsertionCount = currentInsertionCount;
            this.numBits = numBits;
            this.numHashFunctions = numHashFunctions;
            this.bitArray = bitArray;
        }

        public void add(String element) {
            byte[] bytes = element.getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < numHashFunctions; i++) {
                int hash = MurmurHash3Util.hash32(bytes, i);
                int bitIndex = Math.floorMod(hash, numBits);
                bitArray.set(bitIndex);
            }
            currentInsertionCount++;
        }

        public boolean mightContain(String element) {
            byte[] bytes = element.getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < numHashFunctions; i++) {
                int hash = MurmurHash3Util.hash32(bytes, i);
                int bitIndex = Math.floorMod(hash, numBits);
                if (!bitArray.get(bitIndex)) {
                    return false;
                }
            }
            return true;
        }

        public boolean isFull() {
            return currentInsertionCount >= (maxCapacity * LAYER_FULL_THRESHOLD);
        }

        public double getSaturation() {
            if (numBits == 0) {
                return 0.0;
            }
            return (double) bitArray.cardinality() / (double) numBits;
        }

        public int getNumBits() {
            return numBits;
        }

        public int getNumHashFunctions() {
            return numHashFunctions;
        }

        public int getMaxCapacity() {
            return maxCapacity;
        }

        public int getCurrentInsertionCount() {
            return currentInsertionCount;
        }
    }

    private final List<BloomFilterLayer> layers;
    private final double initialFalsePositiveRate;
    private final double tighteningRatio;
    private int currentCapacity;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public ScalableBloomFilter(int initialCapacity, double targetFalsePositiveRate) {
        this(initialCapacity, targetFalsePositiveRate, DEFAULT_TIGHTENING_RATIO);
    }

    public ScalableBloomFilter(int initialCapacity, double targetFalsePositiveRate, double tighteningRatio) {
        FilterSizingCalculator.validateParameters(initialCapacity, targetFalsePositiveRate);
        if (tighteningRatio <= 0.0 || tighteningRatio >= 1.0) {
            throw new IllegalArgumentException("tighteningRatio must be in (0.0, 1.0)");
        }
        this.layers = new ArrayList<>();
        this.initialFalsePositiveRate = targetFalsePositiveRate;
        this.tighteningRatio = tighteningRatio;
        this.currentCapacity = initialCapacity;
        this.layers.add(new BloomFilterLayer(currentCapacity, initialFalsePositiveRate));
    }

    public void add(String element) {
        if (element == null) {
            return;
        }
        lock.writeLock().lock();
        try {
            BloomFilterLayer activeLayer = layers.get(layers.size() - 1);
            if (activeLayer.isFull()) {
                currentCapacity *= 2;
                double nextErrorRate = initialFalsePositiveRate * Math.pow(tighteningRatio, layers.size());
                activeLayer = new BloomFilterLayer(currentCapacity, nextErrorRate);
                layers.add(activeLayer);
            }
            activeLayer.add(element);
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
            for (BloomFilterLayer layer : layers) {
                if (layer.mightContain(element)) {
                    return true;
                }
            }
            return false;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int getLayerCount() {
        lock.readLock().lock();
        try {
            return layers.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public double getActiveLayerSaturation() {
        lock.readLock().lock();
        try {
            if (layers.isEmpty()) {
                return 0.0;
            }
            return layers.get(layers.size() - 1).getSaturation();
        } finally {
            lock.readLock().unlock();
        }
    }

    public long getTotalInsertions() {
        lock.readLock().lock();
        try {
            return layers.stream().mapToLong(BloomFilterLayer::getCurrentInsertionCount).sum();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Serializes the entire layered filter state out to a dense binary byte stream.
     */
    public void serialize(OutputStream out) throws IOException {
        lock.readLock().lock();
        try {
            DataOutputStream dos = new DataOutputStream(out);
            dos.writeDouble(this.initialFalsePositiveRate);
            dos.writeDouble(this.tighteningRatio);
            dos.writeInt(this.currentCapacity);
            dos.writeInt(this.layers.size());

            for (BloomFilterLayer layer : layers) {
                dos.writeInt(layer.maxCapacity);
                dos.writeInt(layer.currentInsertionCount);
                dos.writeInt(layer.numBits);
                dos.writeInt(layer.numHashFunctions);

                long[] rawBits = layer.bitArray.toLongArray();
                dos.writeInt(rawBits.length);
                for (long value : rawBits) {
                    dos.writeLong(value);
                }
            }
            dos.flush();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Reconstructs a {@link ScalableBloomFilter} instance from an incoming binary byte stream.
     */
    public static ScalableBloomFilter deserialize(InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);

        double initialRate = dis.readDouble();
        double ratio = dis.readDouble();
        int capacity = dis.readInt();
        int totalLayers = dis.readInt();

        ScalableBloomFilter filter = new ScalableBloomFilter(capacity, initialRate, ratio);
        filter.layers.clear();
        filter.currentCapacity = capacity;

        for (int i = 0; i < totalLayers; i++) {
            int maxCap = dis.readInt();
            int currentCount = dis.readInt();
            int numBits = dis.readInt();
            int numHashFunctions = dis.readInt();

            int longArrayLength = dis.readInt();
            long[] rawBits = new long[longArrayLength];
            for (int j = 0; j < longArrayLength; j++) {
                rawBits[j] = dis.readLong();
            }

            BitSet hydratedBits = new BitSet(numBits);
            hydratedBits.or(BitSet.valueOf(rawBits));
            BloomFilterLayer layer = new BloomFilterLayer(maxCap, currentCount, numBits, numHashFunctions, hydratedBits);
            filter.layers.add(layer);
        }

        return filter;
    }
}
