package com.codingdevs.bloomfilter.core;

import java.nio.charset.StandardCharsets;

/**
 * Production-grade 32-bit MurmurHash3 implementation for uniform bit distribution.
 */
public final class MurmurHash3Util {

    private static final int C1 = 0xcc9e2d51;
    private static final int C2 = 0x1b873593;

    private MurmurHash3Util() {
    }

    public static int hash32(String value, int seed) {
        return hash32(value.getBytes(StandardCharsets.UTF_8), seed);
    }

    public static int hash32(byte[] data, int seed) {
        int h1 = seed;
        int length = data.length;
        int roundedEnd = length & ~3;

        for (int i = 0; i < roundedEnd; i += 4) {
            int k1 = (data[i] & 0xFF)
                    | ((data[i + 1] & 0xFF) << 8)
                    | ((data[i + 2] & 0xFF) << 16)
                    | (data[i + 3] << 24);
            k1 *= C1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= C2;

            h1 ^= k1;
            h1 = Integer.rotateLeft(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
        }

        int k1 = 0;
        switch (length & 3) {
            case 3:
                k1 ^= (data[roundedEnd + 2] & 0xFF) << 16;
                // fallthrough
            case 2:
                k1 ^= (data[roundedEnd + 1] & 0xFF) << 8;
                // fallthrough
            case 1:
                k1 ^= (data[roundedEnd] & 0xFF);
                k1 *= C1;
                k1 = Integer.rotateLeft(k1, 15);
                k1 *= C2;
                h1 ^= k1;
                break;
            default:
                break;
        }

        h1 ^= length;
        h1 ^= (h1 >>> 16);
        h1 *= 0x85ebca6b;
        h1 ^= (h1 >>> 13);
        h1 *= 0xc2b2ae35;
        h1 ^= (h1 >>> 16);

        return h1;
    }
}
