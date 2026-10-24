package com.codingdevs.bloomfilter.redis.tls;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPooled;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.SecureRandom;

/**
 * Factory for creating TLSv1.3 encrypted {@link JedisPooled} connections to Redis Stack.
 */
public final class SecureJedisFactory {

    private SecureJedisFactory() {
    }

    public static JedisPooled createSecureClient(
            String host,
            int port,
            String truststorePath,
            String password) throws Exception {

        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(truststorePath)) {
            trustStore.load(fis, password.toCharArray());
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
        sslContext.init(null, tmf.getTrustManagers(), new SecureRandom());
        SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

        JedisClientConfig config = DefaultJedisClientConfig.builder()
                .ssl(true)
                .sslSocketFactory(sslSocketFactory)
                .connectionTimeoutMillis(2000)
                .socketTimeoutMillis(2000)
                .build();

        return new JedisPooled(new HostAndPort(host, port), config);
    }

    public static JedisPooled createPlainClient(String host, int port) {
        JedisClientConfig config = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(2000)
                .socketTimeoutMillis(2000)
                .build();
        return new JedisPooled(new HostAndPort(host, port), config);
    }
}
