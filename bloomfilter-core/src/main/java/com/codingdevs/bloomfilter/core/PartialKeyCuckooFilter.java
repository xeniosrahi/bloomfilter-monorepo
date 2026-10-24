package com.codingdevs.bloomfilter.core;

import java.util.Random;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Cuckoo Filter using Partial-Key Cuckoo Hashing (4-slot buckets and 1-byte non-zero fingerprints).
 * Alternate bucket index is computed exclusively from the current index and the fingerprint via XOR:
 *   i1 = hash(x) mod numBuckets
 *   i2 = (i1 ^ hash(fingerprint)) mod numBuckets
 */
public class PartialKeyCuckooFilter {

    public static final int BUCKET_SIZE = 4;
    private static final int DEFAULT_MAX_KICKS = 500;

    private final byte[][] table;
    private final int numBuckets;
    private final int maxKicks;
    private final Random random = new Random(0xC0C00L);
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private int size = 0;

    public PartialKeyCuckooFilter(int numBuckets) {
        this(numBuckets, DEFAULT_MAX_KICKS);
    }

    public PartialKeyCuckooFilter(int numBuckets, int maxKicks) {
        if (numBuckets <= 0 || maxKicks <= 0) {
            throw new IllegalArgumentException("numBuckets and maxKicks must be > 0");
        }
        // Round numBuckets up to a power of two so XOR alternate indexing is cleanly symmetric
        int pow2Buckets = Integer.highestOneBit(numBuckets - 1) << 1;
        this.numBuckets = Math.max(4, pow2Buckets);
        this.maxKicks = maxKicks;
        this.table = new byte[this.numBuckets][BUCKET_SIZE];
    }

    /**
     * Generates a 1-byte fingerprint and guarantees it is never 0 (0 signifies an empty slot).
     */
    private byte getFingerprint(String element) {
        int hash = MurmurHash3Util.hash32(element, 0x1f2e3d4c);
        byte fp = (byte) ((hash ^ (hash >>> 16)) & 0xFF);
        return fp == 0 ? (byte) 1 : fp;
    }

    private int getHash1(String element) {
        int hash = MurmurHash3Util.hash32(element, 0x7f4a7c15);
        return Math.floorMod(hash, numBuckets);
    }

    /**
     * Computes the alternate bucket index using ONLY the current bucket index and fingerprint via XOR.
     * Since numBuckets is a power of 2, (i1 ^ mask) % numBuckets is strictly involutory:
     * getAltIndex(getAltIndex(i1, fp), fp) == i1.
     */
    int getAltIndex(int currentIndex, byte fingerprint) {
        int fpHash = MurmurHash3Util.hash32(new byte[]{fingerprint}, 0x5bd1e995);
        int mask = Math.floorMod(fpHash, numBuckets);
        if (mask == 0) {
            mask = 1;
        }
        return (currentIndex ^ mask) & (numBuckets - 1);
    }

    public boolean insert(String element) {
        if (element == null) {
            return false;
        }
        byte fp = getFingerprint(element);
        int i1 = getHash1(element);
        int i2 = getAltIndex(i1, fp);

        lock.writeLock().lock();
        try {
            if (putIntoBucket(i1, fp) || putIntoBucket(i2, fp)) {
                size++;
                return true;
            }

            int curIndex = random.nextBoolean() ? i1 : i2;
            for (int kick = 0; kick < maxKicks; kick++) {
                int slot = random.nextInt(BUCKET_SIZE);
                byte evictedFp = table[curIndex][slot];
                table[curIndex][slot] = fp;

                curIndex = getAltIndex(curIndex, evictedFp);
                if (putIntoBucket(curIndex, evictedFp)) {
                    size++;
                    return true;
                }
                fp = evictedFp;
            }
            return false; // Filter is critically full
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean contains(String element) {
        if (element == null) {
            return false;
        }
        byte fp = getFingerprint(element);
        int i1 = getHash1(element);
        int i2 = getAltIndex(i1, fp);

        lock.readLock().lock();
        try {
            return bucketContains(i1, fp) || bucketContains(i2, fp);
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean delete(String element) {
        if (element == null) {
            return false;
        }
        byte fp = getFingerprint(element);
        int i1 = getHash1(element);
        int i2 = getAltIndex(i1, fp);

        lock.writeLock().lock();
        try {
            if (removeFromBucket(i1, fp) || removeFromBucket(i2, fp)) {
                size--;
                return true;
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private boolean putIntoBucket(int bucketIndex, byte fp) {
        for (int i = 0; i < BUCKET_SIZE; i++) {
            if (table[bucketIndex][i] == 0) {
                table[bucketIndex][i] = fp;
                return true;
            }
        }
        return false;
    }

    private boolean bucketContains(int bucketIndex, byte fp) {
        for (int i = 0; i < BUCKET_SIZE; i++) {
            if (table[bucketIndex][i] == fp) {
                return true;
            }
        }
        return false;
    }

    private boolean removeFromBucket(int bucketIndex, byte fp) {
        for (int i = 0; i < BUCKET_SIZE; i++) {
            if (table[bucketIndex][i] == fp) {
                table[bucketIndex][i] = 0;
                return true;
            }
        }
        return false;
    }

    public int getNumBuckets() {
        return numBuckets;
    }

    public int size() {
        lock.readLock().lock();
        try {
            return size;
        } finally {
            lock.readLock().unlock();
        }
    }
}
