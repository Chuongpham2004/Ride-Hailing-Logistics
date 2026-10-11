package com.rhl.user.application.notification;

import com.rhl.user.domain.user.ContactChannel;

import java.time.Duration;

/**
 * Delivers one-time codes by email or SMS. {@code rhl.notifications.sender} picks the sender:
 * {@code log} for development, {@code smtp} for email; no SMS provider is chosen yet.
 */
public interface NotificationSender {

    enum Purpose {
        VERIFY_CONTACT,
        PASSWORD_RESET
    }

    /**
     * @param destination the normalized email or E.164 phone number
     * @param validFor    shown to the person so they know when to ask again
     */
    void sendCode(ContactChannel channel, String destination, Purpose purpose, String code, Duration validFor);
}
