package com.codingdevs.bloomfilter.service.config;

import com.codingdevs.bloomfilter.redis.compaction.AutomatedStreamManager;
import com.codingdevs.bloomfilter.redis.compaction.RedisStreamCompactor;
import com.codingdevs.bloomfilter.redis.recovery.RedisBloomStateTransfer;
import com.codingdevs.bloomfilter.redis.telemetry.BloomTelemetryMetrics;
import com.codingdevs.bloomfilter.redis.tls.SecureJedisFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPooled;

import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class SecureRedisConfig {

    private static final Logger log = LoggerFactory.getLogger(SecureRedisConfig.class);

    @Value("${redis.host:localhost}")
    private String host;

    @Value("${redis.port:6379}")
    private int port;

    @Value("${redis.ssl-enabled:false}")
    private boolean sslEnabled;

    @Value("${redis.truststore-path:/certs/truststore.p12}")
    private String truststorePath;

    @Value("${redis.truststore-password:securepassword123}")
    private String password;

    @Value("${bloom.stream-key:bloom-stream}")
    private String streamKey;

    @Value("${bloom.filter-key:global_bloom_l2}")
    private String filterKey;

    @Value("${bloom.stream-max-entries:50000}")
    private long streamMaxEntries;

    @Bean(destroyMethod = "close")
    public JedisPooled jedisPooled() throws Exception {
        if (sslEnabled && Files.exists(Path.of(truststorePath))) {
            log.info("Initializing TLSv1.3 Secure JedisPooled client to {}:{} using truststore {}",
                    host, port, truststorePath);
            return SecureJedisFactory.createSecureClient(host, port, truststorePath, password);
        }
        log.info("Initializing standard TCP JedisPooled client to {}:{} (SSL={})", host, port, sslEnabled);
        return SecureJedisFactory.createPlainClient(host, port);
    }

    @Bean
    public BloomTelemetryMetrics bloomTelemetryMetrics(MeterRegistry registry) {
        return new BloomTelemetryMetrics(registry);
    }

    @Bean(destroyMethod = "close")
    public RedisStreamCompactor redisStreamCompactor(JedisPooled jedisPooled) {
        return new RedisStreamCompactor(jedisPooled, streamKey, streamMaxEntries);
    }

    @Bean(destroyMethod = "close")
    public AutomatedStreamManager automatedStreamManager(RedisStreamCompactor compactor) {
        AutomatedStreamManager manager = new AutomatedStreamManager(compactor);
        manager.startAutoCompaction();
        return manager;
    }

    @Bean(destroyMethod = "close")
    public RedisBloomStateTransfer redisBloomStateTransfer(JedisPooled jedisPooled) {
        return new RedisBloomStateTransfer(jedisPooled, filterKey);
    }
}
