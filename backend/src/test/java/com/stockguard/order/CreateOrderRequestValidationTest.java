package com.stockguard.order;

import com.stockguard.order.dto.CreateOrderRequest;
import com.stockguard.order.dto.OrderItemRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CreateOrderRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    @DisplayName("Empty items collection is rejected by @NotEmpty")
    void emptyItemsCollectionRejected() {
        CreateOrderRequest request = new CreateOrderRequest(
                "CUST-001",
                "EXT-001",
                "USD",
                List.of()
        );

        Set<ConstraintViolation<CreateOrderRequest>> violations = validator.validate(request);
        assertThat(violations).isNotEmpty();
        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                .contains("Order must contain at least one item");
    }

    @Test
    @DisplayName("Items collection with exactly 100 items passes validation")
    void hundredItemsPassesValidation() {
        List<OrderItemRequest> items = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            items.add(new OrderItemRequest(UUID.randomUUID(), 2L, new BigDecimal("19.99")));
        }

        CreateOrderRequest request = new CreateOrderRequest(
                "CUST-100",
                "EXT-100",
                "USD",
                items
        );

        Set<ConstraintViolation<CreateOrderRequest>> violations = validator.validate(request);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Items collection with 101 items is rejected by @Size(max = 100)")
    void hundredAndOneItemsRejected() {
        List<OrderItemRequest> items = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            items.add(new OrderItemRequest(UUID.randomUUID(), 1L, new BigDecimal("9.99")));
        }

        CreateOrderRequest request = new CreateOrderRequest(
                "CUST-101",
                "EXT-101",
                "USD",
                items
        );

        Set<ConstraintViolation<CreateOrderRequest>> violations = validator.validate(request);
        assertThat(violations).isNotEmpty();
        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                .contains("Order items list must not exceed 100 items");
    }

    @Test
    @DisplayName("Nested item validation rejects invalid order items (null productId, zero/negative quantity, null unit price)")
    void nestedItemValidationWorks() {
        // Item with null productId
        CreateOrderRequest nullProductReq = new CreateOrderRequest(
                "CUST-002",
                "EXT-002",
                "USD",
                List.of(new OrderItemRequest(null, 5L, new BigDecimal("10.00")))
        );
        Set<ConstraintViolation<CreateOrderRequest>> nullProductViolations = validator.validate(nullProductReq);
        assertThat(nullProductViolations).isNotEmpty();
        assertThat(nullProductViolations)
                .extracting(ConstraintViolation::getMessage)
                .contains("Product ID is required");

        // Item with quantity <= 0
        CreateOrderRequest zeroQtyReq = new CreateOrderRequest(
                "CUST-003",
                "EXT-003",
                "USD",
                List.of(new OrderItemRequest(UUID.randomUUID(), 0L, new BigDecimal("10.00")))
        );
        Set<ConstraintViolation<CreateOrderRequest>> zeroQtyViolations = validator.validate(zeroQtyReq);
        assertThat(zeroQtyViolations).isNotEmpty();
        assertThat(zeroQtyViolations)
                .extracting(ConstraintViolation::getMessage)
                .contains("Quantity must be strictly positive");

        // Item with null unit price
        CreateOrderRequest nullPriceReq = new CreateOrderRequest(
                "CUST-004",
                "EXT-004",
                "USD",
                List.of(new OrderItemRequest(UUID.randomUUID(), 5L, null))
        );
        Set<ConstraintViolation<CreateOrderRequest>> nullPriceViolations = validator.validate(nullPriceReq);
        assertThat(nullPriceViolations).isNotEmpty();
        assertThat(nullPriceViolations)
                .extracting(ConstraintViolation::getMessage)
                .contains("Unit price is required");
    }
}
