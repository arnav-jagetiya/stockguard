package com.stockguard.security.service;

import com.stockguard.audit.service.AuditLogService;
import com.stockguard.organization.domain.Organization;
import com.stockguard.organization.domain.OrganizationMembership;
import com.stockguard.organization.domain.Role;
import com.stockguard.organization.repository.OrganizationMembershipRepository;
import com.stockguard.organization.repository.OrganizationRepository;
import com.stockguard.security.config.SecurityProperties;
import com.stockguard.security.domain.User;
import com.stockguard.security.exception.AuthenticationFailedException;
import com.stockguard.security.model.LoginCommand;
import com.stockguard.security.model.LoginResult;
import com.stockguard.security.model.RefreshResult;
import com.stockguard.security.model.RegisterCommand;
import com.stockguard.security.repository.UserRepository;
import com.stockguard.shared.exception.DuplicateResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.UUID;

/**
 * High-level application service orchestrating authentication lifecycles:
 * login credential verification, self-service organization registration,
 * access JWT issuance, opaque refresh token rotation, and session revocation.
 */
@Service
@org.springframework.context.annotation.Profile("!test")
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);
    private static final String TOKEN_TYPE_BEARER = "Bearer";
    private static final String DUMMY_HASH = "$2a$12$e8Y7iW5U8V3W3Lq7y9zMwe8iX.B2m1oKqP0Q3sT8rU7v6w5x4y3z2";

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final SecurityProperties securityProperties;
    private final AuditLogService auditLogService;

    @Autowired
    public AuthenticationService(
            UserRepository userRepository,
            OrganizationRepository organizationRepository,
            OrganizationMembershipRepository membershipRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            RefreshTokenService refreshTokenService,
            SecurityProperties securityProperties,
            @Autowired(required = false) AuditLogService auditLogService
    ) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.membershipRepository = membershipRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.securityProperties = securityProperties;
        this.auditLogService = auditLogService;
    }

    public AuthenticationService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            RefreshTokenService refreshTokenService,
            SecurityProperties securityProperties
    ) {
        this(userRepository, null, null, passwordEncoder, jwtService, refreshTokenService, securityProperties, null);
    }

    /**
     * Authenticates user credentials, creates an authentication session, and returns
     * an access JWT and initial raw refresh token.
     *
     * @param command Login credentials and client context
     * @return LoginResult containing access token and raw refresh token
     * @throws AuthenticationFailedException on invalid credentials or inactive user
     */
    public LoginResult login(LoginCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Login command must not be null");
        }

        String normalizedEmail = User.normalizeEmail(command.email());
        Optional<User> userOpt = userRepository.findByNormalizedEmail(normalizedEmail);

        if (userOpt.isEmpty()) {
            // Defeat timing attacks with constant-time dummy password check
            passwordEncoder.matches(command.password(), DUMMY_HASH);
            log.warn("Authentication failed: user not found for normalized email");
            throw new AuthenticationFailedException("Invalid email or password");
        }

        User user = userOpt.get();

        if (user.getPasswordHash() == null || !passwordEncoder.matches(command.password(), user.getPasswordHash())) {
            log.warn("Authentication failed: invalid password for user {}", user.getId());
            throw new AuthenticationFailedException("Invalid email or password");
        }

        if (!user.isActive()) {
            log.warn("Authentication failed: account is inactive for user {}", user.getId());
            throw new AuthenticationFailedException("Invalid email or password");
        }

        RefreshTokenService.InitialSessionResult sessionResult = refreshTokenService.createInitialSession(
                user.getId(),
                command.ipAddress(),
                command.userAgent()
        );

        String accessToken = jwtService.generateAccessToken(
                user.getId(),
                user.getNormalizedEmail(),
                sessionResult.session().getId()
        );

        long expiresInSeconds = (long) securityProperties.getJwt().getAccessTokenExpirationMinutes() * 60L;

        return new LoginResult(
                accessToken,
                sessionResult.rawRefreshToken(),
                expiresInSeconds,
                TOKEN_TYPE_BEARER,
                user.getId(),
                user.getEmail(),
                sessionResult.session().getId()
        );
    }

    /**
     * Atomically registers a new user, creates their primary organization, assigns them
     * the initial ADMIN membership, creates an authenticated session, and returns
     * an access JWT with raw refresh token.
     *
     * @param command Validated registration payload and client context
     * @return LoginResult containing access token and raw refresh token
     */
    @Transactional
    public LoginResult register(RegisterCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Register command must not be null");
        }
        if (command.fullName() == null || command.fullName().trim().length() < 2) {
            throw new IllegalArgumentException("Full name must be at least 2 characters");
        }
        if (command.email() == null || command.email().trim().isEmpty()) {
            throw new IllegalArgumentException("Email must not be blank");
        }
        if (command.organizationName() == null || command.organizationName().trim().length() < 2) {
            throw new IllegalArgumentException("Organization name must be at least 2 characters");
        }
        if (command.password() == null || command.password().length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }

        String normalizedEmail = User.normalizeEmail(command.email());

        if (userRepository.findByNormalizedEmail(normalizedEmail).isPresent()) {
            log.warn("Registration rejected: normalized email already exists");
            throw new DuplicateResourceException("An account with this email already exists");
        }

        // 1. Persist new User with BCrypt password hash
        String passwordHash = passwordEncoder.encode(command.password());
        User user = User.create(command.email().trim(), passwordHash);
        user = userRepository.saveAndFlush(user);

        // 2. Persist new Organization
        Organization organization = Organization.create(command.organizationName().trim());
        if (organizationRepository != null) {
            organization = organizationRepository.saveAndFlush(organization);
        }

        // 3. Persist OrganizationMembership with Role.ADMIN
        OrganizationMembership membership = OrganizationMembership.create(
                organization.getId(),
                user.getId(),
                Role.ADMIN
        );
        if (membershipRepository != null) {
            membershipRepository.saveAndFlush(membership);
        }

        // 4. Audit organization and initial member creation
        if (auditLogService != null) {
            try {
                auditLogService.logMemberAdded(organization.getId(), user.getId(), user.getId(), "ADMIN");
            } catch (Exception e) {
                log.warn("Failed to audit initial organization member registration: {}", e.getMessage());
            }
        }

        // 5. Establish initial session and issue tokens
        RefreshTokenService.InitialSessionResult sessionResult = refreshTokenService.createInitialSession(
                user.getId(),
                command.ipAddress(),
                command.userAgent()
        );

        String accessToken = jwtService.generateAccessToken(
                user.getId(),
                user.getNormalizedEmail(),
                sessionResult.session().getId()
        );

        long expiresInSeconds = (long) securityProperties.getJwt().getAccessTokenExpirationMinutes() * 60L;

        return new LoginResult(
                accessToken,
                sessionResult.rawRefreshToken(),
                expiresInSeconds,
                TOKEN_TYPE_BEARER,
                user.getId(),
                user.getEmail(),
                sessionResult.session().getId()
        );
    }

    /**
     * Rotates a presented opaque refresh token and issues a new access token.
     *
     * @param rawRefreshToken Presented raw opaque refresh token string
     * @return RefreshResult containing newly issued access token and successor refresh token
     */
    public RefreshResult refresh(String rawRefreshToken) {
        RefreshTokenService.RotationOutcome outcome = refreshTokenService.rotateRefreshToken(rawRefreshToken);

        User user = userRepository.findById(outcome.userId())
                .orElseThrow(() -> new AuthenticationFailedException("User account not found"));

        if (!user.isActive()) {
            refreshTokenService.revokeSessionById(outcome.sessionId(), "USER_INACTIVE");
            throw new AuthenticationFailedException("User account is inactive");
        }

        String accessToken = jwtService.generateAccessToken(
                user.getId(),
                user.getNormalizedEmail(),
                outcome.sessionId()
        );

        long expiresInSeconds = (long) securityProperties.getJwt().getAccessTokenExpirationMinutes() * 60L;

        return new RefreshResult(
                accessToken,
                outcome.rawRefreshToken(),
                expiresInSeconds,
                TOKEN_TYPE_BEARER,
                user.getId(),
                user.getEmail(),
                outcome.sessionId()
        );
    }

    /**
     * Logs out an active session by revoking its session family.
     *
     * @param rawRefreshToken Presented raw refresh token
     */
    public void logout(String rawRefreshToken) {
        refreshTokenService.revokeSessionByToken(rawRefreshToken, "LOGOUT");
    }

    /**
     * Revokes a session family explicitly by its session UUID.
     */
    public void revokeSession(UUID sessionId, String reason) {
        refreshTokenService.revokeSessionById(sessionId, reason);
    }
}
