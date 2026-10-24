package com.codingdevs.bloomfilter.redis.recovery;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPooled;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisBloomStateTransferTest {

    @Test
    void backupStopsAtZeroIteratorAndCopiesChunkPayloads() {
        JedisPooled jedis = mock(JedisPooled.class);
        byte[] firstPayload = {1, 2, 3};

        when(jedis.bfScanDump("source-filter", 0L))
                .thenReturn(Map.entry(7L, firstPayload));
        // BF.SCANDUMP signals completion with iterator 0 and an empty payload.
        when(jedis.bfScanDump("source-filter", 7L))
                .thenReturn(Map.entry(0L, new byte[0]));

        RedisBloomStateTransfer transfer =
                new RedisBloomStateTransfer(jedis, "source-filter");

        List<BloomDumpChunk> chunks = transfer.backupBloomFilterState();

        assertEquals(1, chunks.size());
        assertEquals(7L, chunks.get(0).iterator());
        assertArrayEquals(new byte[]{1, 2, 3}, chunks.get(0).data());

        // Neither changing the original Redis response nor a returned array can
        // mutate a chunk that is queued for restore.
        firstPayload[0] = 99;
        byte[] returnedPayload = chunks.get(0).data();
        returnedPayload[0] = 88;
        assertArrayEquals(new byte[]{1, 2, 3}, chunks.get(0).data());

        verify(jedis).bfScanDump("source-filter", 0L);
        verify(jedis).bfScanDump("source-filter", 7L);
    }

    @Test
    void restorePassesOriginalIteratorsAndPayloadsToRedis() {
        JedisPooled jedis = mock(JedisPooled.class);
        byte[] firstPayload = {10, 20};
        byte[] secondPayload = {30, 40};
        RedisBloomStateTransfer transfer =
                new RedisBloomStateTransfer(jedis, "source-filter");

        transfer.restoreBloomFilterState("target-filter", List.of(
                new BloomDumpChunk(7L, firstPayload),
                new BloomDumpChunk(9L, secondPayload)));

        verify(jedis).bfLoadChunk(eq("target-filter"), eq(7L), aryEq(firstPayload));
        verify(jedis).bfLoadChunk(eq("target-filter"), eq(9L), aryEq(secondPayload));
    }
}
