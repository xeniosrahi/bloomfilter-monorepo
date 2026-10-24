plugins {
    java
    id("org.springframework.boot") version "3.3.4" apply false
}

group = "com.codingdevs.bloomfilter"
version = "1.0.0"

allprojects {
    group = rootProject.group
    version = rootProject.version
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }

    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.10.3")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher:1.10.3")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
