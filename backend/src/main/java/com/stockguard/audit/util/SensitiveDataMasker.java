package com.stockguard.audit.util;

import java.util.*;

/**
 * Defense-in-depth utility for sanitizing sensitive credentials, tokens, and secrets
 * from audit metadata and state maps.
 * <p>
 * NOTE: This is a secondary safety net. The primary security boundary is the compile-time
 * allowlist of domain-specific fields accepted by {@code AuditLogService} methods.
 */
public final class SensitiveDataMasker {

    private static final Set<String> SENSITIVE_KEYWORDS = Set.of(
            "password",
            "rawpassword",
            "passwordhash",
            "passwd",
            "secret",
            "signingkey",
            "activekey",
            "token",
            "accesstoken",
            "refreshtoken",
            "tokenhash",
            "jwt",
            "credentials",
            "apikey",
            "clientsecret",
            "cookie",
            "setcookie",
            "authorization",
            "creditcard",
            "cvv",
            "ssn"
    );

    public static final String MASKED_VALUE = "[PROTECTED]";

    private SensitiveDataMasker() {
    }

    /**
     * Recursively sanitizes any sensitive keys present in the map, returning a new map
     * with sensitive values replaced by {@value #MASKED_VALUE}.
     */
    public static Map<String, Object> mask(Map<String, ?> input) {
        if (input == null) {
            return null;
        }
        Map<String, Object> masked = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : input.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (isSensitiveKey(key)) {
                masked.put(key, MASKED_VALUE);
            } else if (value instanceof Map<?, ?> nestedMap) {
                masked.put(key, mask((Map<String, ?>) nestedMap));
            } else if (value instanceof Collection<?> collection) {
                masked.put(key, maskCollection(collection));
            } else {
                masked.put(key, value);
            }
        }
        return Collections.unmodifiableMap(masked);
    }

    private static List<Object> maskCollection(Collection<?> collection) {
        List<Object> maskedList = new ArrayList<>(collection.size());
        for (Object item : collection) {
            if (item instanceof Map<?, ?> nestedMap) {
                maskedList.add(mask((Map<String, ?>) nestedMap));
            } else {
                maskedList.add(item);
            }
        }
        return Collections.unmodifiableList(maskedList);
    }

    public static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[_\\-\\s]", "");
        for (String sensitive : SENSITIVE_KEYWORDS) {
            if (normalized.contains(sensitive)) {
                return true;
            }
        }
        return false;
    }
}
