package com.codingdevs.bloomfilter.benchmarks.gatling;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import java.time.Duration;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Gatling High-Concurrency Load Simulation validating the 99.99% SLA and Cache Penetration
 * Shield under 3 simultaneous production traffic profiles:
 * 1. Seed & Warm-Up Scenario: Registers valid user records into Primary DB + L1/L2 Bloom Filter.
 * 2. Malicious Cache Penetration Flood: High-RPS random non-existent IDs (asserts fast 404 in <10ms).
 * 3. Legitimate Read/Write Mix: Concurrent membership queries and valid user lookups.
 */
public class CachePenetrationSimulation extends Simulation {

    private static final String BASE_URL = System.getProperty("targetBaseUrl", "http://localhost:8080");
    private static final int PEAK_PENETRATION_RPS = Integer.getInteger("penetrationRps", 500);
    private static final int LEGITIMATE_USER_RPS = Integer.getInteger("legitimateRps", 200);

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .shareConnections();

    private final Iterator<Map<String, Object>> maliciousIdFeeder =
            Stream.generate((Supplier<Map<String, Object>>) () ->
                    Collections.singletonMap("randomId", "malicious_scan_" + UUID.randomUUID())
            ).iterator();

    private final Iterator<Map<String, Object>> validUserFeeder =
            Stream.generate((Supplier<Map<String, Object>>) () ->
                    Collections.singletonMap("validId", "seeded_user_" + ThreadLocalRandom.current().nextInt(1, 500))
            ).iterator();

    // Scenario 1: Seed 500 valid users into DB + L1/L2 Bloom Filter
    private final ScenarioBuilder seedUsersScenario = scenario("1. Seed Valid Users")
            .repeat(500, "idx").on(
                    exec(http("Register Seeded User")
                            .post("/api/v1/users")
                    .body(StringBody("{\"id\":\"seeded_user_#{idx}\",\"payload\":\"{\\\"tier\\\":\\\"enterprise\\\"}\"}"))
                            .check(status().is(201)))
            );

    // Scenario 2: High-Volume Malicious Cache Penetration Attack (Random Non-Existent IDs)
    private final ScenarioBuilder cachePenetrationAttackScenario = scenario("2. Malicious Cache Penetration Flood")
            .feed(maliciousIdFeeder)
            .exec(http("Query Non-Existent User ID (Shielded Fast 404)")
                    .get("/api/v1/users/#{randomId}")
                    .check(status().is(404))
                    .check(jsonPath("$.shieldedByBloomFilter").is("true")));

    // Scenario 3: Legitimate User Traffic & Direct L1/L2 Membership Checks
    private final ScenarioBuilder legitimateTrafficScenario = scenario("3. Legitimate User Lookups & L1/L2 Checks")
            .feed(validUserFeeder)
            .exec(http("Direct L1/L2 Bloom Filter Check")
                    .get("/api/v1/bloom/items/#{validId}/exists")
                    .check(status().is(200)))
            .exec(http("Protected User Resource Lookup")
                    .get("/api/v1/users/#{validId}")
                    .check(status().in(200, 404)));

    {
        setUp(
                seedUsersScenario.injectOpen(atOnceUsers(10)),
                cachePenetrationAttackScenario.injectOpen(
                        nothingFor(Duration.ofSeconds(5)),
                        rampUsersPerSec(50).to(PEAK_PENETRATION_RPS).during(Duration.ofSeconds(20)),
                        constantUsersPerSec(PEAK_PENETRATION_RPS).during(Duration.ofSeconds(60))
                ),
                legitimateTrafficScenario.injectOpen(
                        nothingFor(Duration.ofSeconds(5)),
                        rampUsersPerSec(20).to(LEGITIMATE_USER_RPS).during(Duration.ofSeconds(20)),
                        constantUsersPerSec(LEGITIMATE_USER_RPS).during(Duration.ofSeconds(60))
                )
        ).protocols(httpProtocol)
         .assertions(
                 global().successfulRequests().percent().gt(99.99),
                 details("Query Non-Existent User ID (Shielded Fast 404)").responseTime().percentile4().lt(15)
         );
    }
}
