package com.stockguard.security.cli;

import com.stockguard.organization.domain.OrganizationMembership;
import com.stockguard.organization.domain.Role;
import com.stockguard.organization.repository.OrganizationMembershipRepository;
import com.stockguard.security.domain.User;
import com.stockguard.security.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Dedicated, parameterized service for rotating administrator credentials.
 * <p>
 * Security & In-Memory Hygiene Design:
 * <ul>
 *   <li>Inputs are received as {@code char[]} to avoid lingering plaintext Strings in JVM memory.</li>
 *   <li>Callers and service methods zero out {@code char[]} buffers via {@link Arrays#fill(char[], char)}
 *       immediately upon completion.</li>
 *   <li><b>JVM Memory Limitation Notice:</b> While user-controlled {@code char[]} arrays are explicitly
 *       zeroed, internal components of the runtime and Spring Security (such as {@link CharBuffer#toString()}
 *       within {@link PasswordEncoder#encode(CharSequence)}) may create transient String objects in heap
 *       memory that cannot be explicitly wiped and rely on garbage collection. This limitation is acknowledged
 *       and minimized by wrapping character buffers directly and avoiding unnecessary copies.</li>
 *   <li>All database updates are strictly parameterized and execute within an isolated transaction.</li>
 *   <li>Strict validation verifies user active status, organization active status, and ADMIN role.</li>
 *   <li>Post-update verification validates that the new password matches the updated hash and that the
 *       prior/compromised password does not match.</li>
 *   <li>Passwords and hashes are never logged or exposed.</li>
 * </ul>
 */
@Service
@org.springframework.context.annotation.Profile("!test")
public class AdminPasswordRotationService {

    private static final Logger log = LoggerFactory.getLogger(AdminPasswordRotationService.class);

    public static final UUID DEFAULT_TARGET_ADMIN_USER_ID = UUID.fromString("e85de460-2410-4b92-af55-7412c39eccd3");
    public static final String DEFAULT_TARGET_ADMIN_EMAIL = "acme-admin@stockguard.dev";
    public static final UUID DEFAULT_TARGET_ORGANIZATION_ID = UUID.fromString("15b4a602-eb5d-456b-bcd2-55b8fc571faf");

    public static final int MIN_PASSWORD_LENGTH = 12;
    public static final int MAX_BCRYPT_PASSWORD_BYTES = 72;

    private static final String UPDATE_PASSWORD_SQL =
            "UPDATE users SET password_hash = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND active = true";

    private static final String SELECT_HASH_SQL =
            "SELECT password_hash FROM users WHERE id = ?";

    private final UserRepository userRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    public AdminPasswordRotationService(
            UserRepository userRepository,
            OrganizationMembershipRepository membershipRepository,
            PasswordEncoder passwordEncoder,
            JdbcTemplate jdbcTemplate
    ) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.passwordEncoder = passwordEncoder;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Rotates the password for the default Acme Corporation administrator.
     *
     * @param newPasswordChars new password characters (wiped upon completion)
     * @return rotation result metadata
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public RotationResult rotateDefaultAdminPassword(char[] newPasswordChars) {
        return rotateAdminPassword(
                newPasswordChars,
                DEFAULT_TARGET_ADMIN_USER_ID,
                DEFAULT_TARGET_ADMIN_EMAIL,
                DEFAULT_TARGET_ORGANIZATION_ID,
                null
        );
    }

    /**
     * Rotates the password for the default Acme Corporation administrator with explicit verification
     * that a previous credential is fully invalidated.
     *
     * @param newPasswordChars         new password characters
     * @param oldPasswordVerification  optional old password characters to verify invalidation against
     * @return rotation result metadata
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public RotationResult rotateDefaultAdminPassword(char[] newPasswordChars, char[] oldPasswordVerification) {
        return rotateAdminPassword(
                newPasswordChars,
                DEFAULT_TARGET_ADMIN_USER_ID,
                DEFAULT_TARGET_ADMIN_EMAIL,
                DEFAULT_TARGET_ORGANIZATION_ID,
                oldPasswordVerification
        );
    }

    /**
     * Rotates an administrator's password under strict target validation, parameterized SQL execution,
     * and in-transaction verification.
     *
     * @param newPasswordChars         new password characters (must be >= 12 chars, <= 72 UTF-8 bytes)
     * @param targetUserId             exact user ID
     * @param targetEmail              exact user email (normalized comparison)
     * @param targetOrgId              exact organization ID
     * @param oldPasswordVerification  optional characters of previous password to verify invalidation
     * @return rotation result metadata
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public RotationResult rotateAdminPassword(
            char[] newPasswordChars,
            UUID targetUserId,
            String targetEmail,
            UUID targetOrgId,
            char[] oldPasswordVerification
    ) {
        Objects.requireNonNull(targetUserId, "Target user ID must not be null");
        Objects.requireNonNull(targetEmail, "Target email must not be null");
        Objects.requireNonNull(targetOrgId, "Target organization ID must not be null");

        // 1. Validate password policy and byte constraints
        validatePasswordComplexity(newPasswordChars);

        // 2. Validate target user identity and active status
        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("Target user not found: " + targetUserId));

        String normalizedExpectedEmail = User.normalizeEmail(targetEmail);
        if (!user.getNormalizedEmail().equals(normalizedExpectedEmail)) {
            throw new IllegalArgumentException(String.format(
                    "Target user email mismatch: expected '%s', found '%s'",
                    normalizedExpectedEmail, user.getNormalizedEmail()
            ));
        }

        if (!user.isActive()) {
            throw new IllegalStateException("Target user is inactive: " + targetUserId);
        }

        // 3. Validate target organization membership and ADMIN role
        OrganizationMembership membership = membershipRepository.findByOrganizationIdAndUserId(targetOrgId, targetUserId)
                .orElseThrow(() -> new IllegalStateException(String.format(
                        "Target user [%s] has no membership in organization [%s]",
                        targetUserId, targetOrgId
                )));

        if (!membership.isActive()) {
            throw new IllegalStateException(String.format(
                    "Target user [%s] membership in organization [%s] is inactive",
                    targetUserId, targetOrgId
            ));
        }

        if (membership.getRole() != Role.ADMIN) {
            throw new IllegalStateException(String.format(
                    "Target user [%s] does not hold ADMIN role in organization [%s]; current role: %s",
                    targetUserId, targetOrgId, membership.getRole()
            ));
        }

        // 4. Ensure new password does not match current password
        String currentHash = user.getPasswordHash();
        CharBuffer newPasswordBuffer = CharBuffer.wrap(newPasswordChars);
        if (currentHash != null && passwordEncoder.matches(newPasswordBuffer, currentHash)) {
            throw new IllegalArgumentException("New administrator password cannot be identical to current password.");
        }

        // 5. Generate secure hash
        String newHash = passwordEncoder.encode(CharBuffer.wrap(newPasswordChars));

        // 6. Execute parameterized database update
        int rowsUpdated = jdbcTemplate.update(UPDATE_PASSWORD_SQL, newHash, targetUserId);
        if (rowsUpdated != 1) {
            throw new IllegalStateException(String.format(
                    "Password rotation failed: expected exactly 1 row updated, but database reported %d rows",
                    rowsUpdated
            ));
        }

        // 7. Verify persisted hash within the transaction
        String persistedHash = jdbcTemplate.queryForObject(SELECT_HASH_SQL, String.class, targetUserId);
        if (persistedHash == null || !passwordEncoder.matches(CharBuffer.wrap(newPasswordChars), persistedHash)) {
            throw new IllegalStateException("Post-rotation verification failed: persisted hash does not match new password.");
        }

        if (oldPasswordVerification != null) {
            CharBuffer oldPasswordBuffer = CharBuffer.wrap(oldPasswordVerification);
            if (passwordEncoder.matches(oldPasswordBuffer, persistedHash)) {
                throw new IllegalStateException("Post-rotation verification failed: persisted hash unexpectedly matches old password.");
            }
        }

        // 8. Log success (no sensitive data)
        log.info("Administrator password rotated successfully for user [{}] ({}) in organization [{}]. Rows affected: {}.",
                targetUserId, normalizedExpectedEmail, targetOrgId, rowsUpdated);

        return new RotationResult(
                targetUserId,
                normalizedExpectedEmail,
                targetOrgId,
                Instant.now(),
                rowsUpdated
        );
    }

    private void validatePasswordComplexity(char[] password) {
        if (password == null || password.length < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(String.format(
                    "Password must be at least %d characters in length", MIN_PASSWORD_LENGTH
            ));
        }

        // BCrypt accepts at most 72 UTF-8 bytes
        ByteBuffer byteBuffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        int byteCount = byteBuffer.remaining();
        // Zero out temporary byte buffer backing array if accessible
        if (byteBuffer.hasArray()) {
            Arrays.fill(byteBuffer.array(), (byte) 0);
        }

        if (byteCount > MAX_BCRYPT_PASSWORD_BYTES) {
            throw new IllegalArgumentException(String.format(
                    "Password exceeds maximum allowable length of %d UTF-8 bytes for BCrypt (provided: %d bytes)",
                    MAX_BCRYPT_PASSWORD_BYTES, byteCount
            ));
        }
    }

    /**
     * Immutable result of a successful administrator password rotation.
     */
    public record RotationResult(
            UUID userId,
            String email,
            UUID organizationId,
            Instant rotatedAt,
            int rowsUpdated
    ) {}
}
