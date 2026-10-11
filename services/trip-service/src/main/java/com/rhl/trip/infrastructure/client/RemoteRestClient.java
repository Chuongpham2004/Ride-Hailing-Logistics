package com.rhl.trip.infrastructure.client;

import com.rhl.common.web.CorrelationId;
import com.rhl.trip.TripServiceProperties;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;

/**
 * The {@link RestClient} every remote client in this package is built on: timeouts from
 * configuration and the caller's correlation ID on each request. A base URL of the form
 * {@code lb://<service>} is resolved through the service registry on every call; any other URL
 * is a fixed address and is used as is.
 */
final class RemoteRestClient {

    private static final String REGISTRY_SCHEME = "lb://";

    private RemoteRestClient() {
    }

    static RestClient create(RestClient.Builder builder, TripServiceProperties.Remote config,
                             LoadBalancerClient loadBalancer) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(config.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(config.readTimeout());
        String baseUrl = config.baseUrl();
        if (baseUrl.startsWith(REGISTRY_SCHEME)) {
            String serviceId = baseUrl.substring(REGISTRY_SCHEME.length());
            baseUrl = "http://" + serviceId;
            builder.requestInterceptor(viaRegistry(serviceId, loadBalancer));
        }
        return builder.baseUrl(baseUrl)
                .requestFactory(factory)
                .requestInterceptor((request, body, execution) -> {
                    String correlationId = CorrelationId.current();
                    if (correlationId != null) {
                        request.getHeaders().set(CorrelationId.HEADER, correlationId);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }

    private static ClientHttpRequestInterceptor viaRegistry(String serviceId, LoadBalancerClient loadBalancer) {
        return (request, body, execution) -> {
            ServiceInstance instance = loadBalancer.choose(serviceId);
            if (instance == null) {
                // Surfaces as ResourceAccessException: retried and counted like a remote that is down.
                throw new IOException("No instance of " + serviceId + " in the service registry");
            }
            URI target = loadBalancer.reconstructURI(instance, request.getURI());
            return execution.execute(new HttpRequestWrapper(request) {
                @Override
                public URI getURI() {
                    return target;
                }
            }, body);
        };
    }
}
