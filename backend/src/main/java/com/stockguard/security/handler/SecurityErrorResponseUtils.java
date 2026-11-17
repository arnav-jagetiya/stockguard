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
        writeJsonError(response, status, error, null, message, path);
    }

    public static void writeJsonError(
            HttpServletResponse response,
            int status,
            String error,
            String code,
            String message,
            String path
    ) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");

        String escapedError = error != null ? escapeJson(error) : "";
        String escapedCode = code != null ? escapeJson(code) : "";
        String escapedMessage = message != null ? escapeJson(message) : "";
        String escapedPath = path != null ? escapeJson(path) : "";

        StringBuilder sb = new StringBuilder("{");
        sb.append("\"timestamp\":\"").append(Instant.now()).append("\",");
        sb.append("\"status\":").append(status).append(",");
        sb.append("\"error\":\"").append(escapedError).append("\",");
        if (code != null && !code.isBlank()) {
            sb.append("\"code\":\"").append(escapedCode).append("\",");
        }
        sb.append("\"message\":\"").append(escapedMessage).append("\",");
        sb.append("\"path\":\"").append(escapedPath).append("\"");
        sb.append("}");

        response.getWriter().write(sb.toString());
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
