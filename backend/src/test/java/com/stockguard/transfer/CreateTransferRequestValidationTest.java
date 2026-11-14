package com.stockguard.transfer;

import com.stockguard.transfer.dto.CreateTransferRequest;
import com.stockguard.transfer.dto.TransferItemRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CreateTransferRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    @DisplayName("Empty items collection is rejected by @NotEmpty")
    void emptyItemsCollectionRejected() {
        CreateTransferRequest request = new CreateTransferRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(),
                "Empty transfer"
        );

        Set<ConstraintViolation<CreateTransferRequest>> violations = validator.validate(request);
        assertThat(violations).isNotEmpty();
        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                .contains("Transfer items list cannot be empty");
    }

    @Test
    @DisplayName("Items collection with exactly 100 items passes validation")
    void hundredItemsPassesValidation() {
        List<TransferItemRequest> items = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            items.add(new TransferItemRequest(UUID.randomUUID(), 10L));
        }

        CreateTransferRequest request = new CreateTransferRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                items,
                "100 items transfer"
        );

        Set<ConstraintViolation<CreateTransferRequest>> violations = validator.validate(request);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Items collection with 101 items is rejected by @Size(max = 100)")
    void hundredAndOneItemsRejected() {
        List<TransferItemRequest> items = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            items.add(new TransferItemRequest(UUID.randomUUID(), 5L));
        }

        CreateTransferRequest request = new CreateTransferRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                items,
                "101 items transfer"
        );

        Set<ConstraintViolation<CreateTransferRequest>> violations = validator.validate(request);
        assertThat(violations).isNotEmpty();
        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                .contains("Transfer items list must not exceed 100 items");
    }

    @Test
    @DisplayName("Nested item validation rejects invalid transfer items (null productId, zero/negative quantity)")
    void nestedItemValidationWorks() {
        // Item with null productId
        CreateTransferRequest nullProductReq = new CreateTransferRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(new TransferItemRequest(null, 10L)),
                "Invalid item"
        );
        Set<ConstraintViolation<CreateTransferRequest>> nullProductViolations = validator.validate(nullProductReq);
        assertThat(nullProductViolations).isNotEmpty();
        assertThat(nullProductViolations)
                .extracting(ConstraintViolation::getMessage)
                .contains("Product ID is required");

        // Item with quantity <= 0
        CreateTransferRequest zeroQtyReq = new CreateTransferRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(new TransferItemRequest(UUID.randomUUID(), 0L)),
                "Invalid item"
        );
        Set<ConstraintViolation<CreateTransferRequest>> zeroQtyViolations = validator.validate(zeroQtyReq);
        assertThat(zeroQtyViolations).isNotEmpty();
        assertThat(zeroQtyViolations)
                .extracting(ConstraintViolation::getMessage)
                .contains("Quantity must be at least 1");
    }
}
