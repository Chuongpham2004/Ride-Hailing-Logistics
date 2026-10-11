package com.rhl.user.infrastructure.notification;

import com.rhl.user.UserServiceProperties;
import com.rhl.user.application.notification.NotificationSender.Purpose;
import com.rhl.user.domain.user.ContactChannel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SmtpNotificationSenderTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);

    @Test
    void emailsTheCodeFromTheConfiguredAddress() {
        sender("noreply@rhl.test").sendCode(ContactChannel.EMAIL, "driver@example.com", Purpose.VERIFY_CONTACT,
                "123456", Duration.ofMinutes(10));

        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(message.capture());
        assertThat(message.getValue().getFrom()).isEqualTo("noreply@rhl.test");
        assertThat(message.getValue().getTo()).containsExactly("driver@example.com");
        assertThat(message.getValue().getText()).contains("123456").contains("10 phút");
        assertThat(message.getValue().getSubject()).doesNotContain("123456");
    }

    @Test
    void passwordResetHasItsOwnSubject() {
        sender("noreply@rhl.test").sendCode(ContactChannel.EMAIL, "driver@example.com", Purpose.PASSWORD_RESET,
                "654321", Duration.ofMinutes(10));

        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(message.capture());
        assertThat(message.getValue().getSubject()).contains("đặt lại mật khẩu");
    }

    @Test
    void phoneCodesAreNotSentByEmail() {
        sender("noreply@rhl.test").sendCode(ContactChannel.PHONE, "+84912345678", Purpose.VERIFY_CONTACT,
                "123456", Duration.ofMinutes(10));

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void aFailingMailServerDoesNotFailTheCaller() {
        doThrow(new MailSendException("connection refused")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatCode(() -> sender("noreply@rhl.test").sendCode(ContactChannel.EMAIL, "driver@example.com",
                Purpose.VERIFY_CONTACT, "123456", Duration.ofMinutes(10))).doesNotThrowAnyException();
    }

    @Test
    void refusesToStartWithoutAFromAddress() {
        assertThatThrownBy(() -> sender(" ")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMTP_FROM");
    }

    private SmtpNotificationSender sender(String from) {
        UserServiceProperties properties = new UserServiceProperties(null, null, null, null, null, null, null,
                null, null, new UserServiceProperties.Notifications("smtp", from));
        return new SmtpNotificationSender(mailSender, Runnable::run, properties);
    }
}
