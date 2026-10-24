# `com.codingdevs.bloomfilter` — Production-Grade Distributed Bloom Filter Monorepo

An enterprise Java 21 / Spring Boot 3.3.4 multi-module monorepo implementing high-throughput, space-efficient probabilistic data structures and a **99.99% Uptime SLA Hybrid L1/L2 Distributed Synchronization Architecture** backed by **Redis Stack (RedisBloom)**, **Redis Streams**, **mTLS (TLSv1.3)**, **Prometheus/Micrometer**, **Kubernetes (Kustomize + Helm)**, **Chaos Mesh**, and **Gatling / JMH / JMeter Benchmarks**.

## Monorepo Architecture & Module Layout

```text
bloomfilter-monorepo/
├── pom.xml                                  # Parent Maven POM (Java 21, Spring Boot 3.3.4, Guava 33, Jedis 5.1.2, Gatling, JMH)
├── Makefile                                 # Build, TLS certs, Docker Compose, K8s, Helm, Chaos & Benchmark targets
├── Dockerfile                               # Multi-stage Maven + Eclipse Temurin 21 JRE Alpine (ZGC, non-root)
├── docker-compose.yml                       # Multi-node local cluster + Redis Stack + Prometheus + Grafana
├── docker-compose.tls.yml                   # mTLS-enforced Redis Stack (--tls-port 6379) + Secure Spring Boot Node
├── scripts/
│   ├── generate-tls-certs.sh                # Generates Root CA, Redis server certs, and PKCS12 truststore
│   └── run-chaos-tests.sh                   # Executes Chaos Mesh latency & partition experiments
├── bloomfilter-core/                        # Pure Java Probabilistic Data Structures Library
│   └── src/main/java/com/codingdevs/bloomfilter/core/
│       ├── FilterSizingCalculator.java      # Optimal m, k, MB footprint, and Cuckoo fingerprint sizing formulas
│       ├── MurmurHash3Util.java             # 32-bit MurmurHash3 implementation for uniform bit distribution
│       ├── StandardBloomFilter.java         # Thread-safe BitSet Bloom Filter with double hashing
│       ├── GuavaBloomFilterAdapter.java     # Production Guava Murmur3 BloomFilter wrapper
│       ├── CountingBloomFilter.java         # 8-bit byte[] counter filter supporting safe deletions
│       ├── PartialKeyCuckooFilter.java      # 4-slot bucket Partial-Key Cuckoo Filter using XOR alternate indexing
│       └── ScalableBloomFilter.java         # Dynamic 80%-threshold layered filter + dense binary serialization
├── bloomfilter-redis-sdk/                   # Distributed L1/L2 Redis Synchronization & Operational SDK
│   └── src/main/java/com/codingdevs/bloomfilter/redis/
│       ├── tls/SecureJedisFactory.java      # TLSv1.3 SSLContext + PKCS12 Truststore JedisPooled factory
│       ├── pubsub/DistributedBloomManager.java # L1/L2 Hybrid manager with Redis Pub/Sub real-time broadcast
│       ├── streams/ResilientBloomManager.java  # 99.99% SLA L1/L2 manager with Redis Streams (XADD/XREADGROUP/XACK)
│       ├── compaction/RedisStreamCompactor.java # XTRIM MAXLEN ~ compaction with XINFO GROUPS lag safeguard
│       ├── compaction/AutomatedStreamManager.java # Background 10-minute ScheduledExecutorService daemon
│       ├── recovery/RedisBloomStateTransfer.java # Binary chunk backup/restore via BF.SCANDUMP & BF.LOADCHUNK
│       ├── recovery/ClusterBootstrapRecovery.java # Node cold-start L1 rehydration routine
│       ├── rotation/BloomFilterRotationRunner.java # Zero-Downtime Active-Passive Dual-Filter Pointer Switch
│       └── telemetry/BloomTelemetryMetrics.java # Micrometer counters & gauges for Prometheus alerting
├── bloomfilter-sync-service/                # Production Spring Boot 3.3.4 Microservice
│   └── src/main/java/com/codingdevs/bloomfilter/service/
│       ├── BloomSyncServiceApplication.java
│       ├── config/SecureRedisConfig.java
│       ├── engine/BloomEcosystemEngine.java
│       ├── engine/CachePenetrationGuardService.java
│       └── controller/BloomFilterController.java
├── bloomfilter-benchmarks/                  # Gatling Load Simulations, JMH Microbenchmarks & JMeter Plan
│   ├── src/main/java/com/codingdevs/bloomfilter/benchmarks/jmh/FilterThroughputBenchmark.java
│   ├── src/test/java/com/codingdevs/bloomfilter/benchmarks/gatling/CachePenetrationSimulation.java
│   └── jmeter/cache-penetration-test-plan.jmx
├── helm/bloomfilter-cluster/                # Parameterized Helm Chart (Staging & Production values)
├── k8s/
│   ├── base/                                # Kustomize manifests (Namespace, ConfigMap, Secret, Redis, App, HPA, PDB, Ingress, NetPol)
│   └── chaos/                               # Chaos Mesh NetworkChaos (50ms latency, 30s partition) & PodChaos
├── observability/
│   ├── prometheus/                          # prometheus.yml & alert-rules.yml (PromQL SLA alerts)
│   └── grafana/                             # dashboard-bloom-filter.json
└── .github/workflows/ci-cd.yml              # Automated Maven test, mTLS cert gen, Docker build, Kustomize & Helm CI
```

## Quick Start

### 1. Build and Run Unit Tests
```bash
mvn clean test
```

### 2. Launch Multi-Node Cluster with Docker Compose
```bash
# Standard cluster (Redis Stack + 2 Spring Boot Nodes + Prometheus + Grafana)
make up

# mTLS Encrypted Cluster (Generates Root CA, Redis TLS certs & PKCS12 truststore first)
make up-tls
```

### 3. Deploy via Helm or Kustomize
```bash
# Deploy with Helm (Staging or Production values)
make helm-install
make helm-prod

# Or deploy via Kustomize
make k8s-apply
```

### 4. Run Gatling, JMH & JMeter Benchmarks
```bash
# Run Gatling Cache Penetration Load Simulation (asserts >=99.99% success & <15ms p99 fast-404)
make benchmark-gatling

# Run OpenJDK JMH In-Memory Filter Throughput Microbenchmarks
make benchmark-jmh

# Run Apache JMeter Test Plan in CLI mode
make benchmark-jmeter
```
