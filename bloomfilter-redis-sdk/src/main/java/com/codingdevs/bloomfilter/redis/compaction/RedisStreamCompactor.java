package com.codingdevs.bloomfilter.redis.compaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.XTrimParams;
import redis.clients.jedis.resps.StreamGroupInfo;

import java.util.List;

/**
 * Stream compaction utility executing {@code XTRIM streamKey MAXLEN ~ targetMaxEntries}
 * with a consumer-lag safeguard inspecting {@code XINFO GROUPS} pending counts before pruning.
 */
public class RedisStreamCompactor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamCompactor.class);
    private static final long MAX_SAFE_PENDING_LAG = 500L;

    private final JedisPooled jedis;
    private final boolean ownsClient;
    private final String streamKey;
    private final long targetMaxEntries;

    public RedisStreamCompactor(String redisHost, int port, String streamKey, long targetMaxEntries) {
        this(new JedisPooled(redisHost, port), true, streamKey, targetMaxEntries);
    }

    public RedisStreamCompactor(JedisPooled jedis, String streamKey, long targetMaxEntries) {
        this(jedis, false, streamKey, targetMaxEntries);
    }

    private RedisStreamCompactor(JedisPooled jedis, boolean ownsClient, String streamKey, long targetMaxEntries) {
        this.jedis = jedis;
        this.ownsClient = ownsClient;
        this.streamKey = streamKey;
        this.targetMaxEntries = targetMaxEntries;
    }

    /**
     * Executes the stream compaction cycle using {@code XTRIM MAXLEN ~}.
     * Checks consumer group pending count first to prevent trimming unread messages from lagging nodes.
     */
    public long runCompaction() {
        try {
            long initialLength = getStreamLength();
            if (initialLength <= targetMaxEntries) {
                return 0L;
            }

            long maxPending = getMaxConsumerGroupPendingLag();
            if (maxPending > MAX_SAFE_PENDING_LAG) {
                log.warn("Skipping aggressive XTRIM on [{}]: active consumer group has {} pending messages (threshold {}).",
                        streamKey, maxPending, MAX_SAFE_PENDING_LAG);
                return 0L;
            }

            log.info("Starting stream compaction for key [{}]. Current count: {}", streamKey, initialLength);

            XTrimParams trimParams = XTrimParams.xTrimParams()
                    .maxLen(targetMaxEntries)
                    .approximateTrimming();

            long deletedCount = jedis.xtrim(streamKey, trimParams);
            long currentLength = getStreamLength();

            log.info("Compaction complete for [{}]. Removed approx {} entries. Stream length is now {}",
                    streamKey, deletedCount, currentLength);
            return deletedCount;
        } catch (Exception e) {
            log.error("Failed executing Redis Stream Compaction on [{}]: {}", streamKey, e.getMessage());
            return 0L;
        }
    }

    public long getStreamLength() {
        try {
            return jedis.xlen(streamKey);
        } catch (Exception e) {
            return 0L;
        }
    }

    public long getMaxConsumerGroupPendingLag() {
        try {
            List<StreamGroupInfo> groups = jedis.xinfoGroups(streamKey);
            if (groups == null || groups.isEmpty()) {
                return 0L;
            }
            return groups.stream()
                    .mapToLong(StreamGroupInfo::getPending)
                    .max()
                    .orElse(0L);
        } catch (Exception e) {
            return 0L;
        }
    }

    @Override
    public void close() {
        if (ownsClient && jedis != null) {
            jedis.close();
        }
    }
}
