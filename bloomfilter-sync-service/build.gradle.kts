plugins {
    id("org.springframework.boot")
}

description = "Spring Boot service exposing Bloom Filter APIs and operational telemetry."

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.3.4"))
    implementation(project(":bloomfilter-core"))
    implementation(project(":bloomfilter-redis-sdk"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("io.micrometer:micrometer-registry-prometheus:1.12.0")
    implementation("redis.clients:jedis:5.1.2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.bootJar {
    archiveFileName.set("bloomfilter-sync-service-1.0.0.jar")
}
