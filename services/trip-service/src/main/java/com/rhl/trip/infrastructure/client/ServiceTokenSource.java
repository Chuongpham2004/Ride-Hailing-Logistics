package com.rhl.trip.infrastructure.client;

import java.io.IOException;

/** Bearer token for other services' {@code /internal/**} APIs (README §8.5). */
public interface ServiceTokenSource {

    /** @throws IOException no usable token: the remote call fails like one to a service that is down */
    String token() throws IOException;

    /** The callee refused {@code token} (401): drop it so the next call gets a new one. */
    void invalidate(String token);
}
