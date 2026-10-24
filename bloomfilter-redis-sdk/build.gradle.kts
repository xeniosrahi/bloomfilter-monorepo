description = "Redis-backed L1/L2 Bloom Filter synchronization SDK."

dependencies {
    implementation(project(":bloomfilter-core"))
    implementation("redis.clients:jedis:5.1.2")
    implementation("io.micrometer:micrometer-core:1.12.0")
    implementation("io.micrometer:micrometer-registry-prometheus:1.12.0")
    implementation("org.slf4j:slf4j-api:2.0.13")

    testImplementation("org.mockito:mockito-core:5.12.0")
}
