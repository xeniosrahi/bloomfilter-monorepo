package com.codingdevs.bloomfilter.redis.recovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Binary state transfer using RedisBloom BF.SCANDUMP and BF.LOADCHUNK.
 */
public class RedisBloomStateTransfer implements AutoCloseable {

    private static final Logger log =
            LoggerFactory.getLogger(RedisBloomStateTransfer.class);

    private final JedisPooled jedis;
    private final boolean ownsClient;
    private final String filterKey;

    public RedisBloomStateTransfer(String host, int port) {
        this(new JedisPooled(host, port), true, "global_bloom_l2");
    }

    public RedisBloomStateTransfer(JedisPooled jedis, String filterKey) {
        this(jedis, false, filterKey);
    }

    private RedisBloomStateTransfer(
            JedisPooled jedis, boolean ownsClient, String filterKey) {
        this.jedis = jedis;
        this.ownsClient = ownsClient;
        this.filterKey = filterKey;
    }

    public List<BloomDumpChunk> backupBloomFilterState() {
        return backupBloomFilterState(filterKey);
    }

    public List<BloomDumpChunk> backupBloomFilterState(
            String sourceFilterKey) {

        List<BloomDumpChunk> chunks = new ArrayList<>();
        long iterator = 0L;

        while (true) {
            Map.Entry<Long, byte[]> result =
                    jedis.bfScanDump(sourceFilterKey, iterator);

            if (result == null) {
                break;
            }

            long nextIterator = result.getKey();
            byte[] data = result.getValue();

            // BF.SCANDUMP returns iterator 0 when the scan is complete.
            if (nextIterator == 0L) {
                break;
            }

            if (data != null && data.length > 0) {
                chunks.add(new BloomDumpChunk(nextIterator, data));
            }

            iterator = nextIterator;
        }

        log.info("Extracted {} binary state chunks from Redis filter [{}].",
                chunks.size(), sourceFilterKey);

        return chunks;
    }

    public void restoreBloomFilterState(
            String targetFilterKey,
            List<BloomDumpChunk> chunks) {

        for (BloomDumpChunk chunk : chunks) {
            jedis.bfLoadChunk(
                    targetFilterKey, chunk.iterator(), chunk.data());
        }

        log.info("Target Bloom filter [{}] hydrated from {} binary state chunks.",
                targetFilterKey, chunks.size());
    }

    @Override
    public void close() {
        if (ownsClient && jedis != null) {
            jedis.close();
        }
    }
}
