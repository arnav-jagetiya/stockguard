package com.stockguard.shared.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.context.annotation.Profile;

/**
 * PostgreSQL-backed idempotency service implementing ADR-007.
 * <p>
 * Ensures mutating operations cannot be executed multiple times across concurrent
 * or replayed requests with the same client-supplied idempotency key.
 */
@Service
@Profile("!test")
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final String PRINCIPAL_TYPE_USER = "USER";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public IdempotencyService(
            ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider
    ) {
        this.jdbcTemplate = jdbcTemplateProvider != null ? jdbcTemplateProvider.getIfAvailable() : null;
        this.objectMapper = objectMapperProvider != null
                ? objectMapperProvider.getIfAvailable(IdempotencyService::createDefaultObjectMapper)
                : createDefaultObjectMapper();
    }

    public IdempotencyService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper != null ? objectMapper : createDefaultObjectMapper();
    }

    public static ObjectMapper createDefaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.findAndRegisterModules();
        return mapper;
    }

    public record IdempotentResponse<T>(int statusCode, T body) {}

    /**
     * Executes the supplied action idempotently with an intended response HTTP status code.
     *
     * @param organizationId   Current tenant organization UUID
     * @param principalId      Authenticated user UUID
     * @param operationScope   Action/endpoint identifier (e.g. "INVENTORY_RECEIPT")
     * @param idempotencyKey   Client-provided header key (can be null/empty, in which case logic runs normally)
     * @param requestPayload   Object representing the request to compute the SHA-256 hash
     * @param responseType     Class of the expected response object
     * @param intendedStatus   HTTP status code to persist and return on replay (e.g. 200 or 201)
     * @param action           The business logic to execute if this is the first execution
     * @param <T>              Type of the response payload
     * @return Execution response with status code (either freshly computed or replayed from stored record)
     */
    @Transactional
    public <T> IdempotentResponse<T> executeWithStatus(
            UUID organizationId,
            UUID principalId,
            String operationScope,
            String idempotencyKey,
            Object requestPayload,
            Class<T> responseType,
            int intendedStatus,
            Supplier<T> action
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            // No idempotency key supplied: execute normally without idempotency record
            return new IdempotentResponse<>(intendedStatus, action.get());
        }

        String trimmedKey = idempotencyKey.trim();
        String requestHash = computeSha256(requestPayload);
        UUID newRecordId = UUID.randomUUID();

        // 1. Attempt speculative insert with response_status = NULL
        String insertSql = """
                INSERT INTO idempotency_records (
                    id, organization_id, principal_type, principal_id, operation_scope,
                    idempotency_key, request_hash, response_status, response_body, created_at, expires_at
                ) VALUES (
                    ?, ?, ?, ?, ?,
                    ?, ?, NULL, NULL, NOW(), NOW() + INTERVAL '7 days'
                ) ON CONFLICT (organization_id, principal_type, principal_id, operation_scope, idempotency_key)
                DO NOTHING
                RETURNING id;
                """;

        UUID insertedId = null;
        try {
            insertedId = jdbcTemplate.queryForObject(
                    insertSql,
                    UUID.class,
                    newRecordId,
                    organizationId,
                    PRINCIPAL_TYPE_USER,
                    principalId,
                    operationScope,
                    trimmedKey,
                    requestHash
            );
        } catch (EmptyResultDataAccessException ex) {
            // Handled below when insertedId is null
        }

        if (insertedId != null) {
            // First execution: execute business logic
            T response = action.get();
            persistResponse(insertedId, organizationId, intendedStatus, response);
            return new IdempotentResponse<>(intendedStatus, response);
        }

        // 2. Conflict encountered: lock and inspect the existing record
        String lockSql = """
                SELECT id, request_hash, response_status, response_body
                FROM idempotency_records
                WHERE organization_id = ?
                  AND principal_type = ?
                  AND principal_id = ?
                  AND operation_scope = ?
                  AND idempotency_key = ?
                FOR UPDATE;
                """;

        return jdbcTemplate.query(
                lockSql,
                rs -> {
                    if (!rs.next()) {
                        // The concurrent transaction that held the key rolled back; re-attempt execution
                        T fresh = action.get();
                        UUID fallbackId = UUID.randomUUID();
                        jdbcTemplate.update("""
                                INSERT INTO idempotency_records (
                                    id, organization_id, principal_type, principal_id, operation_scope,
                                    idempotency_key, request_hash, response_status, response_body, created_at, expires_at
                                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, NOW(), NOW() + INTERVAL '7 days')
                                """,
                                fallbackId, organizationId, PRINCIPAL_TYPE_USER, principalId,
                                operationScope, trimmedKey, requestHash, intendedStatus, toJson(fresh));
                        return new IdempotentResponse<>(intendedStatus, fresh);
                    }

                    String existingHash = rs.getString("request_hash");
                    if (!requestHash.equals(existingHash)) {
                        throw new IdempotencyConflictException(
                                "Idempotency key '" + trimmedKey + "' has already been used with a different request payload."
                        );
                    }

                    int status = rs.getInt("response_status");
                    if (rs.wasNull()) {
                        throw new IdempotencyConflictException(
                                "A request with idempotency key '" + trimmedKey + "' is currently being processed."
                        );
                    }

                    String responseBodyJson = rs.getString("response_body");
                    log.info("Replaying idempotently recorded response for key '{}', scope '{}', status {}", trimmedKey, operationScope, status);
                    return new IdempotentResponse<>(status, fromJson(responseBodyJson, responseType));
                },
                organizationId,
                PRINCIPAL_TYPE_USER,
                principalId,
                operationScope,
                trimmedKey
        );
    }

    /**
     * Executes the supplied action idempotently with an intended response HTTP status code.
     *
     * @param organizationId   Current tenant organization UUID
     * @param principalId      Authenticated user UUID
     * @param operationScope   Action/endpoint identifier (e.g. "INVENTORY_RECEIPT")
     * @param idempotencyKey   Client-provided header key (can be null/empty, in which case logic runs normally)
     * @param requestPayload   Object representing the request to compute the SHA-256 hash
     * @param responseType     Class of the expected response object
     * @param intendedStatus   HTTP status code to persist and return on replay (e.g. 200 or 201)
     * @param action           The business logic to execute if this is the first execution
     * @param <T>              Type of the response payload
     * @return Execution response (either freshly computed or replayed from stored record)
     */
    @Transactional
    public <T> T execute(
            UUID organizationId,
            UUID principalId,
            String operationScope,
            String idempotencyKey,
            Object requestPayload,
            Class<T> responseType,
            int intendedStatus,
            Supplier<T> action
    ) {
        return executeWithStatus(organizationId, principalId, operationScope, idempotencyKey, requestPayload, responseType, intendedStatus, action).body();
    }

    /**
     * Executes the supplied action idempotently (defaults to HTTP 200).
     */
    @Transactional
    public <T> T execute(
            UUID organizationId,
            UUID principalId,
            String operationScope,
            String idempotencyKey,
            Object requestPayload,
            Class<T> responseType,
            Supplier<T> action
    ) {
        return execute(organizationId, principalId, operationScope, idempotencyKey, requestPayload, responseType, 200, action);
    }

    private void persistResponse(UUID recordId, UUID organizationId, int status, Object response) {
        String json = toJson(response);
        String updateSql = """
                UPDATE idempotency_records
                SET response_status = ?, response_body = ?::jsonb
                WHERE id = ? AND organization_id = ?;
                """;
        jdbcTemplate.update(updateSql, status, json, recordId, organizationId);
    }

    public String computeSha256(Object payload) {
        if (payload == null) {
            return "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"; // Empty hash
        }
        try {
            String json = objectMapper.writeValueAsString(payload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Failed to calculate SHA-256 payload hash", ex);
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize idempotency response body", ex);
        }
    }

    private <T> T fromJson(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize idempotency response body", ex);
        }
    }
}
