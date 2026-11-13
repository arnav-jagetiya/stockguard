package com.stockguard.security.handler;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.Instant;

/**
 * Utility for formatting consistent, RFC-compliant JSON security error responses.
 */
public final class SecurityErrorResponseUtils {

    private SecurityErrorResponseUtils() {
    }

    public static void writeJsonError(
            HttpServletResponse response,
            int status,
            String error,
            String message,
            String path
    ) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");

        String escapedError = error != null ? escapeJson(error) : "";
        String escapedMessage = message != null ? escapeJson(message) : "";
        String escapedPath = path != null ? escapeJson(path) : "";

        String body = "{" +
                "\"timestamp\":\"" + Instant.now() + "\"," +
                "\"status\":" + status + "," +
                "\"error\":\"" + escapedError + "\"," +
                "\"message\":\"" + escapedMessage + "\"," +
                "\"path\":\"" + escapedPath + "\"" +
                "}";

        response.getWriter().write(body);
    }

    private static String escapeJson(String raw) {
        return raw.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
