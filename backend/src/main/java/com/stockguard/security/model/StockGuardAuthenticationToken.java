package com.stockguard.security.model;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.Collections;

/**
 * Custom Spring Security authentication token holding a {@link StockGuardPrincipal}.
 */
public class StockGuardAuthenticationToken extends AbstractAuthenticationToken {

    private final StockGuardPrincipal principal;
    private final String credentials;

    public StockGuardAuthenticationToken(
            StockGuardPrincipal principal,
            String credentials,
            Collection<? extends GrantedAuthority> authorities
    ) {
        super(authorities != null ? authorities : Collections.emptyList());
        this.principal = principal;
        this.credentials = credentials;
        setAuthenticated(true);
    }

    public StockGuardAuthenticationToken(
            StockGuardPrincipal principal,
            Collection<? extends GrantedAuthority> authorities
    ) {
        this(principal, null, authorities);
    }

    public static StockGuardAuthenticationToken unauthenticated(StockGuardPrincipal principal, String credentials) {
        StockGuardAuthenticationToken token = new StockGuardAuthenticationToken(principal, credentials, Collections.emptyList());
        token.setAuthenticated(false);
        return token;
    }

    @Override
    public Object getCredentials() {
        return credentials;
    }

    @Override
    public StockGuardPrincipal getPrincipal() {
        return principal;
    }
}
