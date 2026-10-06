package com.rhl.user;

import com.rhl.user.domain.driver.DocumentRequirements;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(UserServiceProperties.class)
public class UserServiceApplication {

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(UserServiceApplication.class, args);
    }

    @Bean
    DocumentRequirements documentRequirements(UserServiceProperties properties) {
        return new DocumentRequirements(properties.driver().requiredDocuments(),
                properties.driver().requiredVehicleDocuments());
    }
}
