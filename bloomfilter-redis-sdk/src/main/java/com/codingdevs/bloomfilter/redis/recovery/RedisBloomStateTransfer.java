package com.codingdevs.bloomfilter.redis.recovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.resps.ScanDumpResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Binary state transfer manager using RedisBloom's native {@code BF.SCANDUMP}
 * and {@code BF.LOADCHUNK} commands to snapshot or clone L2 Bloom Filter shards.
 */
public class RedisBloomStateTransfer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisBloomStateTransfer.class);

    private final JedisPooled jedis;
    private final boolean ownsClient;
    private final String filterKey;

    public RedisBloomStateTransfer(String host, int port) {
        this(new JedisPooled(host, port), true, "global_bloom_l2");
    }

    public RedisBloomStateTransfer(JedisPooled jedis, String filterKey) {
        this(jedis, false, filterKey);
    }

    private RedisBloomStateTransfer(JedisPooled jedis, boolean ownsClient, String filterKey) {
        this.jedis = jedis;
        this.ownsClient = ownsClient;
        this.filterKey = filterKey;
    }

    /**
     * Dumps the binary structure of the L2 RedisBloom filter into memory chunks via {@code BF.SCANDUMP}.
     */
    public List<ScanDumpResult> backupBloomFilterState() {
        return backupBloomFilterState(this.filterKey);
    }

    public List<ScanDumpResult> backupBloomFilterState(String sourceFilterKey) {
        List<ScanDumpResult> chunks = new ArrayList<>();
        long iterator = 0L;
        while (true) {
            ScanDumpResult result = jedis.bfScanDump(sourceFilterKey, iterator);
            if (result == null || result.getIterator() == 0L) {
                break;
            }
            chunks.add(result);
            iterator = result.getIterator();
        }
        log.info("Extracted {} binary state chunks from Redis filter [{}].", chunks.size(), sourceFilterKey);
        return chunks;
    }

    /**
     * Restores a target RedisBloom filter shard using compiled binary chunks via {@code BF.LOADCHUNK}.
     */
    public void restoreBloomFilterState(String targetFilterKey, List<ScanDumpResult> chunks) {
        for (ScanDumpResult chunk : chunks) {
            jedis.bfLoadChunk(targetFilterKey, chunk.getIterator(), chunk.getData());
        }
        log.info("Target Bloom filter [{}] hydrated from {} binary state chunks.", targetFilterKey, chunks.size());
    }

    @Override
    public void close() {
        if (ownsClient && jedis != null) {
            jedis.close();
        }
    }
}
