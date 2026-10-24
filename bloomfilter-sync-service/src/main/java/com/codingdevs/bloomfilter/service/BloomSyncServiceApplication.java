package com.codingdevs.bloomfilter.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class BloomSyncServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BloomSyncServiceApplication.class, args);
    }
}
