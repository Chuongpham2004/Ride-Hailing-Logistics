package com.rhl.user.application.notification;

import com.rhl.user.domain.user.ContactChannel;

import java.time.Duration;

/**
 * Delivers one-time codes by email or SMS. The provider is not chosen yet; until then only the
 * development sender exists ({@code rhl.notifications.sender=log}).
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
