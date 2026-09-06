package com.wallet.system.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", "TestOnlySecretKeyForJwtUnitTests1234567890");
        ReflectionTestUtils.setField(jwtUtil, "expirationMs", 3600000L);
    }

    @Test
    void generateToken_thenExtractEmail_returnsOriginalEmail() {
        String token = jwtUtil.generateToken(1L, "alice@example.com", "USER");

        assertThat(jwtUtil.extractEmail(token)).isEqualTo("alice@example.com");
    }

    @Test
    void generateToken_thenExtractUserId_returnsOriginalId() {
        String token = jwtUtil.generateToken(42L, "bob@example.com", "USER");

        assertThat(jwtUtil.extractUserId(token)).isEqualTo(42L);
    }

    @Test
    void generateToken_thenExtractRole_returnsOriginalRole() {
        String token = jwtUtil.generateToken(1L, "admin@example.com", "ADMIN");

        assertThat(jwtUtil.extractRole(token)).isEqualTo("ADMIN");
    }

    @Test
    void isTokenValid_withMatchingEmail_returnsTrue() {
        String token = jwtUtil.generateToken(1L, "alice@example.com", "USER");

        assertThat(jwtUtil.isTokenValid(token, "alice@example.com")).isTrue();
    }

    @Test
    void isTokenValid_withMismatchedEmail_returnsFalse() {
        String token = jwtUtil.generateToken(1L, "alice@example.com", "USER");

        assertThat(jwtUtil.isTokenValid(token, "someone-else@example.com")).isFalse();
    }

    @Test
    void isTokenValid_withExpiredToken_returnsFalse() {
        ReflectionTestUtils.setField(jwtUtil, "expirationMs", -1000L); // already expired
        String token = jwtUtil.generateToken(1L, "alice@example.com", "USER");

        assertThat(jwtUtil.isTokenValid(token, "alice@example.com")).isFalse();
    }
}
