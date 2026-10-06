package com.rhl.user.application.admin;

import com.rhl.common.security.Role;
import com.rhl.user.UserServiceProperties;
import com.rhl.user.application.AuditLog;
import com.rhl.user.domain.user.Identifiers;
import com.rhl.user.domain.user.User;
import com.rhl.user.infrastructure.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.Set;

/**
 * Creates the first administrator from {@code rhl.bootstrap.*} when that email is not
 * registered yet. Staff roles cannot be chosen at sign-up, so without this nobody could grant them.
 */
@Component
@RequiredArgsConstructor
public class BootstrapAdministrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdministrator.class);

    private final UserServiceProperties properties;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuditLog audit;
    private final Clock clock;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        UserServiceProperties.Bootstrap settings = properties.bootstrap();
        if (settings == null || settings.adminEmail() == null || settings.adminEmail().isBlank()) {
            return;
        }
        String email = Identifiers.email(settings.adminEmail());
        if (users.existsByEmail(email)) {
            return;
        }
        if (settings.adminPassword() == null || settings.adminPassword().length() < 12) {
            throw new IllegalStateException("rhl.bootstrap.admin-password must have at least 12 characters");
        }
        User admin = users.save(User.provisionStaff(email, passwordEncoder.encode(settings.adminPassword()),
                "Administrator", Set.of(Role.ADMINISTRATOR), clock.instant()));
        audit.success(null, "ADMINISTRATOR_BOOTSTRAPPED", "USER", admin.getId(), Map.of());
        log.info("Bootstrapped administrator account {}", admin.getId());
    }
}
