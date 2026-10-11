package com.rhl.user.infrastructure.notification;

import com.rhl.user.UserServiceProperties;
import com.rhl.user.application.notification.NotificationSender;
import com.rhl.user.domain.user.ContactChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.concurrent.Executor;

/**
 * Sends one-time codes by email through an SMTP server ({@code spring.mail.*}). The message is
 * handed to a background thread, so the caller answers in the same time whether or not a mail
 * goes out (password reset must not reveal which accounts exist). The code is never logged.
 * There is no SMS provider yet: codes for a phone number are not delivered.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rhl.notifications.sender", havingValue = "smtp")
public class SmtpNotificationSender implements NotificationSender {

    private final JavaMailSender mailSender;
    private final Executor executor;
    private final String from;

    public SmtpNotificationSender(JavaMailSender mailSender,
                                  @Qualifier("applicationTaskExecutor") Executor executor,
                                  UserServiceProperties properties) {
        this.mailSender = mailSender;
        this.executor = executor;
        this.from = properties.notifications().from();
        if (!StringUtils.hasText(from)) {
            throw new IllegalStateException("rhl.notifications.from (SMTP_FROM) is required when "
                    + "rhl.notifications.sender=smtp");
        }
    }

    @Override
    public void sendCode(ContactChannel channel, String destination, Purpose purpose, String code,
                         Duration validFor) {
        if (channel != ContactChannel.EMAIL) {
            log.warn("No SMS provider: {} code for {} was not delivered", purpose,
                    ContactChannel.mask(destination));
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(destination);
        message.setSubject(subject(purpose));
        message.setText(text(purpose, code, validFor));
        executor.execute(() -> deliver(message, purpose, destination));
    }

    private void deliver(SimpleMailMessage message, Purpose purpose, String destination) {
        try {
            mailSender.send(message);
            log.info("Sent {} code by email to {}", purpose, ContactChannel.mask(destination));
        } catch (MailException e) {
            log.error("Could not send {} code by email to {}: {}", purpose, ContactChannel.mask(destination),
                    e.getMessage());
        }
    }

    private static String subject(Purpose purpose) {
        return switch (purpose) {
            case VERIFY_CONTACT -> "[RHL] Mã xác minh email";
            case PASSWORD_RESET -> "[RHL] Mã đặt lại mật khẩu";
        };
    }

    private static String text(Purpose purpose, String code, Duration validFor) {
        String action = switch (purpose) {
            case VERIFY_CONTACT -> "xác minh email";
            case PASSWORD_RESET -> "đặt lại mật khẩu";
        };
        return "Mã " + action + " của bạn là: " + code + "\n\n"
                + "Mã có hiệu lực trong " + validFor.toMinutes() + " phút. Không chia sẻ mã này với bất kỳ ai.\n"
                + "Nếu bạn không yêu cầu, hãy bỏ qua email này.";
    }
}
