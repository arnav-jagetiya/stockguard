package com.stockguard.security.controller;

import com.stockguard.audit.service.AuditLogService;
import com.stockguard.security.dto.*;
import com.stockguard.security.exception.*;
import com.stockguard.security.model.LoginCommand;
import com.stockguard.security.model.LoginResult;
import com.stockguard.security.model.RefreshResult;
import com.stockguard.security.model.StockGuardPrincipal;
import com.stockguard.security.service.AuthenticationService;
import com.stockguard.security.service.TenantMembershipValidator;
import com.stockguard.security.service.UserContextQueryService;
import com.stockguard.security.util.RefreshTokenCookieHelper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * REST controller exposing owner-approved authentication lifecycle endpoints per ADR-010.
 */
@RestController
@RequestMapping("/api/auth")
@Profile("!test")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final Logger securityAuditLog = LoggerFactory.getLogger("com.stockguard.security.audit");

    private final AuthenticationService authenticationService;
    private final UserContextQueryService userContextQueryService;
    private final RefreshTokenCookieHelper cookieHelper;
    private final AuditLogService auditLogService;
    private final TenantMembershipValidator membershipValidator;

    @Autowired
    public AuthController(
            AuthenticationService authenticationService,
            UserContextQueryService userContextQueryService,
            RefreshTokenCookieHelper cookieHelper,
            AuditLogService auditLogService,
            TenantMembershipValidator membershipValidator
    ) {
        this.authenticationService = authenticationService;
        this.userContextQueryService = userContextQueryService;
        this.cookieHelper = cookieHelper;
        this.auditLogService = auditLogService;
        this.membershipValidator = membershipValidator;
    }

    public AuthController(
            AuthenticationService authenticationService,
            UserContextQueryService userContextQueryService,
            RefreshTokenCookieHelper cookieHelper
    ) {
        this(authenticationService, userContextQueryService, cookieHelper, null, null);
    }

    /**
     * Authenticates user credentials, sets the opaque refresh token in an HttpOnly cookie,
     * and returns the access JWT.
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest
    ) {
        String clientIp = servletRequest.getRemoteAddr();
        String userAgent = servletRequest.getHeader(HttpHeaders.USER_AGENT);

        LoginResult result;
        try {
            result = authenticationService.login(
                    new LoginCommand(request.email(), request.password(), clientIp, userAgent)
            );
        } catch (AuthenticationFailedException ex) {
            securityAuditLog.warn("Authentication failed for email: {} from IP: {} - reason: {}",
                    request.email(), clientIp, ex.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized", "message", ex.getMessage()));
        }

        List<OrganizationSummaryDto> organizations =
                userContextQueryService.getUserAvailableOrganizations(result.userId());

        if (organizations.isEmpty()) {
            // Revoke the newly created session and reject login if user has no active memberships
            authenticationService.revokeSession(result.sessionId(), "NO_ACTIVE_MEMBERSHIPS");
            ResponseCookie clearCookie = cookieHelper.buildClearRefreshCookie();
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .header(HttpHeaders.SET_COOKIE, clearCookie.toString())
                    .body(Map.of("error", "Forbidden", "message", "No active organization memberships found for this account"));
        }

        ResponseCookie refreshCookie = cookieHelper.buildRefreshCookie(result.rawRefreshToken());

        UserSummaryDto userSummary = new UserSummaryDto(
                result.userId(),
                result.email(),
                deriveFullName(result.email()),
                true
        );

        AuthTokenResponse responseBody = new AuthTokenResponse(
                result.accessToken(),
                result.tokenType(),
                result.expiresInSeconds(),
                userSummary,
                organizations
        );

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(responseBody);
    }

    /**
     * Rotates a single-use opaque refresh token read from the HttpOnly cookie.
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest servletRequest) {
        Optional<String> rawTokenOpt = cookieHelper.extractRefreshToken(servletRequest);
        if (rawTokenOpt.isEmpty()) {
            securityAuditLog.warn("Refresh failed: missing or empty cookie from IP: {}", servletRequest.getRemoteAddr());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized", "message", "Refresh token cookie is missing or empty"));
        }

        String rawToken = rawTokenOpt.get();
        RefreshResult result;
        try {
            result = authenticationService.refresh(rawToken);
        } catch (TokenReuseException ex) {
            securityAuditLog.warn("Refresh token replay detected from IP: {}", servletRequest.getRemoteAddr());
            log.warn("Refresh token replay detected: {}", ex.getMessage());
            ResponseCookie clearCookie = cookieHelper.buildClearRefreshCookie();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.SET_COOKIE, clearCookie.toString())
                    .body(Map.of("error", "Unauthorized", "message", "Refresh token replay detected. Session family has been revoked."));
        } catch (SessionRevokedException | SessionExpiredException | TokenExpiredException | InvalidTokenException | AuthenticationFailedException ex) {
            securityAuditLog.warn("Refresh failed from IP: {} - reason: {}", servletRequest.getRemoteAddr(), ex.getMessage());
            log.warn("Refresh failed: {}", ex.getMessage());
            ResponseCookie clearCookie = cookieHelper.buildClearRefreshCookie();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.SET_COOKIE, clearCookie.toString())
                    .body(Map.of("error", "Unauthorized", "message", ex.getMessage()));
        }

        ResponseCookie newRefreshCookie = cookieHelper.buildRefreshCookie(result.rawRefreshToken());

        List<OrganizationSummaryDto> organizations =
                userContextQueryService.getUserAvailableOrganizations(result.userId());

        UserSummaryDto userSummary = new UserSummaryDto(
                result.userId(),
                result.email(),
                deriveFullName(result.email()),
                true
        );

        AuthTokenResponse responseBody = new AuthTokenResponse(
                result.accessToken(),
                result.tokenType(),
                result.expiresInSeconds(),
                userSummary,
                organizations
        );

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, newRefreshCookie.toString())
                .body(responseBody);
    }

    /**
     * Terminates an active session and clears the refresh cookie.
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(
            HttpServletRequest servletRequest,
            @AuthenticationPrincipal StockGuardPrincipal principal
    ) {
        // Attempt revocation by cookie token first
        Optional<String> rawTokenOpt = cookieHelper.extractRefreshToken(servletRequest);
        rawTokenOpt.ifPresent(authenticationService::logout);

        // Also revoke session if principal is authenticated
        if (principal != null) {
            authenticationService.revokeSession(principal.sessionId(), "LOGOUT");

            String orgHeader = servletRequest.getHeader("X-Organization-Id");
            if (orgHeader != null && !orgHeader.isBlank()) {
                try {
                    UUID orgId = UUID.fromString(orgHeader.trim());
                    if (membershipValidator != null && auditLogService != null) {
                        var membershipOpt = membershipValidator.validateMembership(principal.userId(), orgId);
                        if (membershipOpt.isPresent() && membershipOpt.get().active()) {
                            auditLogService.logSessionRevoked(orgId, principal.userId(), principal.sessionId(), "LOGOUT");
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to audit session revocation for user {}: {}", principal.userId(), e.getMessage());
                }
            }
        }

        ResponseCookie clearCookie = cookieHelper.buildClearRefreshCookie();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clearCookie.toString())
                .body(Map.of("message", "Successfully logged out"));
    }

    /**
     * Returns the current authenticated user's profile and active organization memberships.
     */
    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(@AuthenticationPrincipal StockGuardPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized", "message", "User not authenticated"));
        }

        try {
            UserMeResponse response = userContextQueryService.getUserMe(principal.userId());
            return ResponseEntity.ok(response);
        } catch (AuthenticationFailedException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized", "message", ex.getMessage()));
        }
    }

    /**
     * Switches the active organization context for the client tab.
     */
    @PostMapping("/switch-org")
    public ResponseEntity<?> switchOrganization(
            @AuthenticationPrincipal StockGuardPrincipal principal,
            @Valid @RequestBody SwitchOrgRequest request
    ) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized", "message", "User not authenticated"));
        }

        try {
            SwitchOrgResponse response = userContextQueryService.switchOrganization(
                    principal.userId(),
                    request.targetOrganizationId()
            );
            return ResponseEntity.ok(response);
        } catch (AccessDeniedException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden", "message", ex.getMessage()));
        } catch (AuthenticationFailedException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized", "message", ex.getMessage()));
        }
    }

    private String deriveFullName(String email) {
        if (email == null || !email.contains("@")) {
            return email;
        }
        String local = email.substring(0, email.indexOf('@')).replace('.', ' ').replace('_', ' ');
        return Character.toUpperCase(local.charAt(0)) + local.substring(1);
    }
}
