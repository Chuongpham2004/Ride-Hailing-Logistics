package com.rhl.payment.api;

import com.rhl.common.web.ApiResponse;
import com.rhl.payment.application.CallbackService;
import com.rhl.payment.infrastructure.provider.WebhookSignature;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payment provider webhooks. Public route without a user token: the body is taken raw because
 * the signature covers the exact bytes that were sent.
 */
@RestController
@RequiredArgsConstructor
public class CallbackController {

    private final CallbackService callbacks;

    @PostMapping("/api/v1/payments/callbacks/{provider}")
    public ApiResponse<CallbackService.Ack> receive(@PathVariable String provider,
                                                    @RequestHeader(value = WebhookSignature.HEADER, required = false)
                                                    String signature,
                                                    @RequestBody String body) {
        return ApiResponse.ok(callbacks.handle(provider, signature, body));
    }
}
