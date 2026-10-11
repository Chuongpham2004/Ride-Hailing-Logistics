package com.rhl.eureka;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

// The parent POM turns the Eureka client off for every test run; the registry itself is built on it.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "eureka.client.enabled=true")
class EurekaServerApplicationTest {

    @LocalServerPort
    private int port;

    @Test
    void servesAnEmptyRegistry() {
        String apps = RestClient.create("http://localhost:" + port).get().uri("/eureka/apps")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class);

        assertThat(apps).contains("\"applications\"").doesNotContain("\"instance\"");
    }
}
