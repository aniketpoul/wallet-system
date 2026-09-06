package com.wallet.system.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.List;

/**
 * Custom principal so controllers/services can pull the authenticated user's
 * id directly from the SecurityContext instead of re-querying by email, and
 * so that "wallet ID must be derived from the authenticated user" is trivial
 * to enforce everywhere.
 */
@Getter
public class AuthenticatedUser extends User {

    private final Long userId;
    private final String email;

    public AuthenticatedUser(Long userId, String email, String password, String role) {
        super(email, password, List.<GrantedAuthority>of(new SimpleGrantedAuthority("ROLE_" + role)));
        this.userId = userId;
        this.email = email;
    }
}
