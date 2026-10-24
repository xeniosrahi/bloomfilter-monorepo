package com.codingdevs.bloomfilter.redis.recovery;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable representation of one RedisBloom BF.SCANDUMP chunk.
 *
 * <p>The iterator is the exact iterator value returned by BF.SCANDUMP and must
 * be passed unchanged to BF.LOADCHUNK. Redis uses iterator {@code 0} to signal
 * completion, so only non-zero iterator/data pairs are represented here.</p>
 */
public final class BloomDumpChunk {

    private final long iterator;
    private final byte[] data;

    public BloomDumpChunk(long iterator, byte[] data) {
        if (iterator <= 0) {
            throw new IllegalArgumentException("iterator must be positive for a dump chunk");
        }
        this.iterator = iterator;
        this.data = Objects.requireNonNull(data, "data").clone();
    }

    public long iterator() {
        return iterator;
    }

    /** Returns a defensive copy of the binary dump payload. */
    public byte[] data() {
        return data.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BloomDumpChunk that)) {
            return false;
        }
        return iterator == that.iterator && Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        return 31 * Long.hashCode(iterator) + Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "BloomDumpChunk{iterator=" + iterator + ", bytes=" + data.length + '}';
    }
}
