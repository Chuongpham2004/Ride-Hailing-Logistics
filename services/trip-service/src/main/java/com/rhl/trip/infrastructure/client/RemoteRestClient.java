package com.rhl.trip.infrastructure.client;

import com.rhl.common.web.CorrelationId;
import com.rhl.trip.TripServiceProperties;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;

/**
 * The {@link RestClient} every remote client in this package is built on: timeouts from
 * configuration, the caller's correlation ID and a service token on each request. A base URL of the form
 * {@code lb://<service>} is resolved through the service registry on every call; any other URL
 * is a fixed address and is used as is.
 */
final class RemoteRestClient {

    private static final String REGISTRY_SCHEME = "lb://";

    private RemoteRestClient() {
    }

    static RestClient create(RestClient.Builder builder, TripServiceProperties.Remote config,
                             LoadBalancerClient loadBalancer, ServiceTokenSource tokens) {
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
                // Last, so that its second attempt goes straight to the network.
                .requestInterceptor(bearer(tokens))
                .build();
    }

    /**
     * {@code Authorization: Bearer <service token>}. A 401 means the token is no longer accepted
     * (for example user-service restarted with a new key): it is dropped and the call is sent once
     * more with a new token.
     */
    static ClientHttpRequestInterceptor bearer(ServiceTokenSource tokens) {
        return (request, body, execution) -> {
            String token = tokens.token();
            request.getHeaders().setBearerAuth(token);
            ClientHttpResponse response = execution.execute(request, body);
            if (!response.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED)) {
                return response;
            }
            response.close();
            tokens.invalidate(token);
            request.getHeaders().setBearerAuth(tokens.token());
            return execution.execute(request, body);
        };
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
