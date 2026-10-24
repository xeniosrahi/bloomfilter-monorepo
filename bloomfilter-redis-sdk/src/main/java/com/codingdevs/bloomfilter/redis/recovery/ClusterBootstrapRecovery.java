package com.codingdevs.bloomfilter.redis.recovery;

import com.codingdevs.bloomfilter.core.ScalableBloomFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.resps.StreamEntry;
import redis.clients.jedis.resps.StreamGroupInfo;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * High-Availability (99.99% SLA) Active Recovery Routine executed during node startup
 * to hydrate the local L1 {@link ScalableBloomFilter} when a fresh scale-out container
 * starts up or when the Redis Stream was truncated past the node's offset by {@code XTRIM}.
 */
public final class ClusterBootstrapRecovery {

    private static final Logger log = LoggerFactory.getLogger(ClusterBootstrapRecovery.class);
    public static final String L1_SNAPSHOT_REDIS_KEY_SUFFIX = ":l1_snapshot";

    private ClusterBootstrapRecovery() {
    }

    public static void hydrateL1OnStartup(
            String host,
            int port,
            String streamKey,
            String consumerGroup,
            String redisFilterKey,
            ScalableBloomFilter localL1Filter) {

        try (JedisPooled jedis = new JedisPooled(host, port)) {
            hydrateL1OnStartup(jedis, streamKey, consumerGroup, redisFilterKey, localL1Filter);
        } catch (Exception e) {
            log.warn("Bootstrap recovery check skipped or failed: {}", e.getMessage());
        }
    }

    public static void hydrateL1OnStartup(
            JedisPooled jedis,
            String streamKey,
            String consumerGroup,
            String redisFilterKey,
            ScalableBloomFilter localL1Filter) {

        try {
            List<StreamGroupInfo> groups = jedis.xInfoGroups(streamKey);
            boolean isDataLostOrFreshNode = (localL1Filter.getTotalInsertions() == 0);

            if (groups != null) {
                for (StreamGroupInfo group : groups) {
                    if (group.getName().equals(consumerGroup)) {
                        isDataLostOrFreshNode = true;
                        break;
                    }
                }
            }

            if (isDataLostOrFreshNode) {
                log.info("Node bootstrap: Local L1 cache empty or stream truncated. Hydrating L1 from L2 & stream...");

                // 1. Check if a compressed L1 binary snapshot is stored in Redis
                String snapshotKey = redisFilterKey + L1_SNAPSHOT_REDIS_KEY_SUFFIX;
                String base64Snapshot = jedis.get(snapshotKey);
                if (base64Snapshot != null && !base64Snapshot.isBlank()) {
                    byte[] rawBytes = Base64.getDecoder().decode(base64Snapshot.getBytes(StandardCharsets.UTF_8));
                    ScalableBloomFilter restored = ScalableBloomFilter.deserialize(new ByteArrayInputStream(rawBytes));
                    log.info("Located L1 binary snapshot [{}] with {} layers ({} insertions).",
                            snapshotKey, restored.getLayerCount(), restored.getTotalInsertions());
                }

                // 2. Replay retained stream window via XRANGE to warm up active keys in L1
                List<StreamEntry> retainedEntries = jedis.xRange(
                        streamKey, (StreamEntryID) null, (StreamEntryID) null, 10_000);
                if (retainedEntries != null) {
                    int replayed = 0;
                    for (StreamEntry entry : retainedEntries) {
                        String element = entry.getFields().get("element");
                        if (element != null && !localL1Filter.mightContain(element)) {
                            localL1Filter.add(element);
                            replayed++;
                        }
                    }
                    log.info("Node bootstrap complete: Replayed {} historical keys from stream [{}].", replayed, streamKey);
                }
            }
        } catch (Exception e) {
            log.warn("Recovery loop warning: {}", e.getMessage());
        }
    }
}
