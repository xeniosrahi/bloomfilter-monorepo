description = "Gatling HTTP load simulation, JMH microbenchmarks and JMeter plans."

dependencies {
    implementation(project(":bloomfilter-core"))
    implementation("org.openjdk.jmh:jmh-core:1.37")
    annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")

    // The existing Gatling simulation lives under src/test/java; keep its layout.
    testImplementation("io.gatling.highcharts:gatling-charts-highcharts:3.11.5")
}

tasks.register<JavaExec>("gatlingRun") {
    group = "verification"
    description = "Run the CachePenetrationSimulation Gatling scenario."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.gatling.app.Gatling")
    args("-s", "com.codingdevs.bloomfilter.benchmarks.gatling.CachePenetrationSimulation")
    systemProperty("targetBaseUrl", providers.gradleProperty("targetBaseUrl").orElse("http://localhost:8080").get())
    systemProperty("penetrationRps", providers.gradleProperty("penetrationRps").orElse("500").get())
    systemProperty("legitimateRps", providers.gradleProperty("legitimateRps").orElse("200").get())
}

tasks.register<JavaExec>("jmh") {
    group = "verification"
    description = "Run JMH microbenchmarks."
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
}
