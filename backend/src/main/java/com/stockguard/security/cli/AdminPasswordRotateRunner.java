package com.stockguard.security.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.Console;
import java.util.Arrays;

/**
 * CLI ApplicationRunner for rotating the administrator password.
 * <p>
 * Activates exclusively when the {@code --stockguard.rotate-admin-password} command line flag is supplied.
 * In the absence of this flag, this runner performs zero actions and allows normal application startup.
 * <p>
 * Security Controls:
 * <ul>
 *   <li>Requires an interactive console ({@link System#console()}) and fails closed if unavailable.</li>
 *   <li>Never echoes password characters to terminal or logs.</li>
 *   <li>Zeros all character buffers immediately after consumption.</li>
 * </ul>
 */
@Component
@Order(5)
@Profile("!test")
public class AdminPasswordRotateRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminPasswordRotateRunner.class);
    public static final String OPTION_ROTATE_ADMIN_PASSWORD = "stockguard.rotate-admin-password";

    @FunctionalInterface
    public interface PasswordPromptReader {
        char[] readPassword(String format, Object... args);
    }

    private final AdminPasswordRotationService rotationService;
    private final PasswordPromptReader promptReader;

    @org.springframework.beans.factory.annotation.Autowired
    public AdminPasswordRotateRunner(AdminPasswordRotationService rotationService) {
        this(rotationService, System.console() != null ? System.console()::readPassword : null);
    }

    // Visible for testing
    AdminPasswordRotateRunner(AdminPasswordRotationService rotationService, PasswordPromptReader promptReader) {
        this.rotationService = rotationService;
        this.promptReader = promptReader;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (args == null || !args.containsOption(OPTION_ROTATE_ADMIN_PASSWORD)) {
            return;
        }

        log.info("Detected command line flag [--{}]. Initiating interactive administrator password rotation...",
                OPTION_ROTATE_ADMIN_PASSWORD);

        if (promptReader == null) {
            String errorMsg = "Interactive console is required for password rotation. System.console() is null.";
            log.error(errorMsg);
            System.err.println("ERROR: " + errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        char[] password = null;
        char[] confirmation = null;

        try {
            password = promptReader.readPassword("Enter new administrator password (min %d characters): ",
                    AdminPasswordRotationService.MIN_PASSWORD_LENGTH);
            if (password == null || password.length == 0) {
                throw new IllegalArgumentException("Password cannot be empty.");
            }

            confirmation = promptReader.readPassword("Confirm new administrator password: ");
            if (confirmation == null || !Arrays.equals(password, confirmation)) {
                throw new IllegalArgumentException("Password confirmation does not match.");
            }

            AdminPasswordRotationService.RotationResult result =
                    rotationService.rotateDefaultAdminPassword(password);

            System.out.printf("%n[SUCCESS] Administrator password rotated successfully.%n");
            System.out.printf("  User ID:         %s%n", result.userId());
            System.out.printf("  Email:           %s%n", result.email());
            System.out.printf("  Organization ID: %s%n", result.organizationId());
            System.out.printf("  Timestamp:       %s%n", result.rotatedAt());
            System.out.printf("  Rows Affected:   %d%n%n", result.rowsUpdated());

        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
            if (confirmation != null) {
                Arrays.fill(confirmation, '\0');
            }
        }
    }
}
