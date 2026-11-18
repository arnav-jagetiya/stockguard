package com.stockguard.security.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminPasswordRotateRunnerTest {

    @Test
    @DisplayName("runner does nothing when command line flag is not present")
    void runnerDoesNothingWithoutFlag() {
        AdminPasswordRotationService service = mock(AdminPasswordRotationService.class);
        AdminPasswordRotateRunner.PasswordPromptReader reader = mock(AdminPasswordRotateRunner.PasswordPromptReader.class);

        AdminPasswordRotateRunner runner = new AdminPasswordRotateRunner(service, reader);
        runner.run(new DefaultApplicationArguments("--other-flag=value"));

        verify(service, never()).rotateDefaultAdminPassword(any());
        verify(reader, never()).readPassword(any(), any());
    }

    @Test
    @DisplayName("runner fails closed when flag is present but console reader is null")
    void runnerFailsClosedWhenConsoleUnavailable() {
        AdminPasswordRotationService service = mock(AdminPasswordRotationService.class);
        AdminPasswordRotateRunner runner = new AdminPasswordRotateRunner(service, null);

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments("--stockguard.rotate-admin-password")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Interactive console is required for password rotation");

        verify(service, never()).rotateDefaultAdminPassword(any());
    }

    @Test
    @DisplayName("runner fails when password confirmation does not match")
    void runnerFailsOnPasswordMismatch() {
        AdminPasswordRotationService service = mock(AdminPasswordRotationService.class);
        char[] pass1 = "FirstPassword123!".toCharArray();
        char[] pass2 = "DifferentPassword456!".toCharArray();

        AdminPasswordRotateRunner.PasswordPromptReader reader = (fmt, args) -> {
            if (fmt.contains("Enter new")) return pass1;
            return pass2;
        };

        AdminPasswordRotateRunner runner = new AdminPasswordRotateRunner(service, reader);

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments("--stockguard.rotate-admin-password")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Password confirmation does not match");

        verify(service, never()).rotateDefaultAdminPassword(any());

        // Verify buffers were zeroed
        assertThat(pass1).containsOnly('\0');
        assertThat(pass2).containsOnly('\0');
    }

    @Test
    @DisplayName("runner invokes service and zeroes buffers on successful matching password entry")
    void runnerInvokesServiceAndZeroesBuffers() {
        AdminPasswordRotationService service = mock(AdminPasswordRotationService.class);
        UUID targetUserId = AdminPasswordRotationService.DEFAULT_TARGET_ADMIN_USER_ID;
        UUID targetOrgId = AdminPasswordRotationService.DEFAULT_TARGET_ORGANIZATION_ID;

        when(service.rotateDefaultAdminPassword(any())).thenReturn(
                new AdminPasswordRotationService.RotationResult(
                        targetUserId,
                        "acme-admin@stockguard.dev",
                        targetOrgId,
                        Instant.now(),
                        1
                )
        );

        char[] pass1 = "ValidNewAdminPassword2026!".toCharArray();
        char[] pass2 = "ValidNewAdminPassword2026!".toCharArray();

        AdminPasswordRotateRunner.PasswordPromptReader reader = (fmt, args) -> {
            if (fmt.contains("Enter new")) return pass1;
            return pass2;
        };

        AdminPasswordRotateRunner runner = new AdminPasswordRotateRunner(service, reader);
        runner.run(new DefaultApplicationArguments("--stockguard.rotate-admin-password"));

        ArgumentCaptor<char[]> captor = ArgumentCaptor.forClass(char[].class);
        verify(service).rotateDefaultAdminPassword(captor.capture());

        // Both original buffers and passed argument must be wiped
        assertThat(pass1).containsOnly('\0');
        assertThat(pass2).containsOnly('\0');
    }
}
