package com.wallet.system.config;

import com.wallet.system.entity.Role;
import com.wallet.system.entity.User;
import com.wallet.system.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * The public API only ever creates USER accounts (POST /auth/register), by
 * design, so an ADMIN account is not reachable through any HTTP endpoint.
 * This seeder creates one fixed admin account on first startup purely so the
 * /admin/** endpoints are reachable for testing/demo purposes. Admins do not
 * get a wallet - they are not wallet owners per the spec.
 *
 * In a real deployment, remove this seeder and provision admins through a
 * separate, tightly controlled internal process instead.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements CommandLineRunner {

    private static final String ADMIN_EMAIL = "admin@wallet.system";
    private static final String ADMIN_DEFAULT_PASSWORD = "Admin@12345";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        if (userRepository.existsByEmail(ADMIN_EMAIL)) {
            return;
        }

        User admin = User.builder()
                .email(ADMIN_EMAIL)
                .password(passwordEncoder.encode(ADMIN_DEFAULT_PASSWORD))
                .role(Role.ADMIN)
                .build();
        userRepository.save(admin);

        log.info("Seeded default admin account -> email: {}, password: {} (change this in production!)",
                ADMIN_EMAIL, ADMIN_DEFAULT_PASSWORD);
    }
}
