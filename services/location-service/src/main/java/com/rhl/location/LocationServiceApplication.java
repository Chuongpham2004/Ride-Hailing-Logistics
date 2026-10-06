package com.rhl.location;

import com.rhl.location.domain.TelemetryPolicy;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(LocationServiceProperties.class)
public class LocationServiceApplication {

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(LocationServiceApplication.class, args);
    }

    @Bean
    TelemetryPolicy telemetryPolicy(LocationServiceProperties properties) {
        return properties.telemetry().policy();
    }
}
