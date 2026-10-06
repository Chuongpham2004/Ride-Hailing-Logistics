package com.rhl.pricing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.time.Duration;
import java.util.TimeZone;

@SpringBootApplication
@EnableConfigurationProperties(PricingServiceProperties.class)
public class PricingServiceApplication {

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(PricingServiceApplication.class, args);
    }

    /**
     * Microsecond ticks, the precision PostgreSQL stores, so a timestamp returned by the API is
     * exactly the one read back later (quote expiry, rule periods).
     */
    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
