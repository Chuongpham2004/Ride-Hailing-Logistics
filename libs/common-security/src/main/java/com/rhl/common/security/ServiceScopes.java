package com.rhl.common.security;

/**
 * What a service token allows (README §8.5). Each scope opens the {@code /internal/**} API of one
 * service; user-service grants a client only the scopes configured for it.
 */
public final class ServiceScopes {

    /** location-service: nearby drivers and a driver's current position. */
    public static final String INTERNAL_LOCATION = "internal:location";
    /** pricing-service: quote validation and cancellation fee preview. */
    public static final String INTERNAL_PRICING = "internal:pricing";

    private ServiceScopes() {
    }
}
