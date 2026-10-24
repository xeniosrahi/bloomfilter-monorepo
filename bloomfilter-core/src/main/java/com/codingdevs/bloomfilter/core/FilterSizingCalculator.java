package com.codingdevs.bloomfilter.core;

/**
 * Utility class providing mathematical sizing calculations for Standard Bloom Filters
 * and Cuckoo Filters based on target capacity (n) and false positive probability (p).
 */
public final class FilterSizingCalculator {

    private static final double LN_2 = Math.log(2.0);
    private static final double LN_2_SQUARED = LN_2 * LN_2;

    private FilterSizingCalculator() {
    }

    /**
     * Optimal Bit Array Size (m):
     * m = - (n * ln(p)) / (ln(2))^2
     */
    public static int optimalNumBits(long expectedElements, double falsePositiveRate) {
        validateParameters(expectedElements, falsePositiveRate);
        long bits = (long) Math.ceil((-expectedElements * Math.log(falsePositiveRate)) / LN_2_SQUARED);
        return (int) Math.min(bits, Integer.MAX_VALUE - 64);
    }

    /**
     * Exact 64-bit bit count for large enterprise capacity planning.
     */
    public static long optimalNumBitsLong(long expectedElements, double falsePositiveRate) {
        validateParameters(expectedElements, falsePositiveRate);
        return (long) Math.ceil((-expectedElements * Math.log(falsePositiveRate)) / LN_2_SQUARED);
    }

    /**
     * Optimal Number of Hash Functions (k):
     * k = max(1, round((m / n) * ln(2)))
     */
    public static int optimalNumHashFunctions(long expectedElements, long numBits) {
        if (expectedElements <= 0 || numBits <= 0) {
            throw new IllegalArgumentException("Expected elements and bit size must be positive.");
        }
        return Math.max(1, (int) Math.round(((double) numBits / expectedElements) * LN_2));
    }

    /**
     * Estimates memory footprint in megabytes (MB) for a given bit count.
     */
    public static double estimateMemoryMegabytes(long numBits) {
        return (double) numBits / 8.0 / 1024.0 / 1024.0;
    }

    /**
     * Calculates the minimum fingerprint bit length (f) for a Cuckoo Filter:
     * f >= ceil(log2(2 * b / p))
     * where b is the number of slots per bucket (typically 4).
     */
    public static int optimalCuckooFingerprintBits(int bucketSize, double falsePositiveRate) {
        if (bucketSize <= 0 || falsePositiveRate <= 0.0 || falsePositiveRate >= 1.0) {
            throw new IllegalArgumentException("Invalid bucketSize or falsePositiveRate.");
        }
        double minBits = Math.log((2.0 * bucketSize) / falsePositiveRate) / LN_2;
        return Math.max(1, (int) Math.ceil(minBits));
    }

    public static void validateParameters(long expectedElements, double falsePositiveRate) {
        if (expectedElements <= 0 || falsePositiveRate <= 0.0 || falsePositiveRate >= 1.0) {
            throw new IllegalArgumentException(
                    "Invalid arguments for filter initialization: expectedElements must be > 0 and falsePositiveRate in (0.0, 1.0).");
        }
    }
}
