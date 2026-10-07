package com.rhl.realtime.application;

import org.springframework.web.socket.CloseStatus;

/**
 * Application close codes (RFC 6455 range 4000-4999). 4401 means: get a new access token and
 * reconnect; 4408 means: reconnect and keep sending PING.
 */
public final class CloseCodes {

    public static final CloseStatus TOKEN_INVALID = new CloseStatus(4401, "TOKEN_INVALID");
    public static final CloseStatus TOKEN_EXPIRED = new CloseStatus(4401, "TOKEN_EXPIRED");
    public static final CloseStatus TOKEN_REVOKED = new CloseStatus(4401, "TOKEN_REVOKED");
    public static final CloseStatus HEARTBEAT_TIMEOUT = new CloseStatus(4408, "HEARTBEAT_TIMEOUT");

    private CloseCodes() {
    }
}
