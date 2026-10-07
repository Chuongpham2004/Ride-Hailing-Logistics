package com.rhl.user.infrastructure.notification;

import com.rhl.user.application.notification.NotificationSender;
import com.rhl.user.domain.user.ContactChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Development stand-in for an email/SMS provider: writes the code to the log so a developer can
 * use it. Never enable outside local development; the destination is masked, the code is not.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rhl.notifications.sender", havingValue = "log")
public class LoggingNotificationSender implements NotificationSender {

    public LoggingNotificationSender() {
        log.warn("Notification codes are written to the log (rhl.notifications.sender=log): development only");
    }

    @Override
    public void sendCode(ContactChannel channel, String destination, Purpose purpose, String code,
                         Duration validFor) {
        log.warn("DEV NOTIFICATION {} to {} ({}): code {} valid for {} min", channel,
                ContactChannel.mask(destination), purpose, code, validFor.toMinutes());
    }
}
