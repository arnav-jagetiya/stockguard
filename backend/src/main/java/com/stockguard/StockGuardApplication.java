package com.stockguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the STOCKGUARD inventory integrity and order reservation platform.
 * <p>
 * STOCKGUARD is architected as a modular monolith where PostgreSQL serves as the
 * single authoritative source of truth for all inventory state.
 */
@SpringBootApplication
public class StockGuardApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockGuardApplication.class, args);
    }
}
