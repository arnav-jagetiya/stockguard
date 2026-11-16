package com.stockguard.demo.runner;

import com.stockguard.demo.config.DemoProperties;
import com.stockguard.demo.service.DemoEnvironmentSeeder;
import com.stockguard.shared.lock.PostgresAdvisoryLockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Opt-in application startup runner for the realistic demo environment.
 * Executes at order 20 (after administrator bootstrap at order 10).
 * Coordinates multi-instance execution via a dedicated-connection PostgreSQL advisory lock.
 */
@Component
@Order(20)
@Profile("!test")
public class DemoSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeedRunner.class);
    public static final long DEMO_SEED_ADVISORY_LOCK_KEY = 0x41434D4553454544L; // 'ACMESEED'

    private final DemoProperties demoProperties;
    private final DemoEnvironmentSeeder demoEnvironmentSeeder;
    private final PostgresAdvisoryLockService advisoryLockService;
    private final Environment environment;

    public DemoSeedRunner(
            DemoProperties demoProperties,
            DemoEnvironmentSeeder demoEnvironmentSeeder,
            PostgresAdvisoryLockService advisoryLockService,
            Environment environment
    ) {
        this.demoProperties = demoProperties;
        this.demoEnvironmentSeeder = demoEnvironmentSeeder;
        this.advisoryLockService = advisoryLockService;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!demoProperties.isSeedEnabled()) {
            log.debug("Demo environment seeding is disabled by configuration.");
            return;
        }

        if (environment != null && environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("Demo environment seeding is strictly forbidden in production (prod profile active).");
        }

        log.info("Attempting to acquire PostgreSQL advisory lock [{}] for demo environment seeding...", DEMO_SEED_ADVISORY_LOCK_KEY);
        boolean executed = advisoryLockService.executeWithLock(DEMO_SEED_ADVISORY_LOCK_KEY, () -> {
            demoEnvironmentSeeder.seedDemoEnvironment(demoProperties);
        });

        if (!executed) {
            log.info("PostgreSQL advisory lock for demo seeding [{}] is already held by another cluster instance. Skipping.", DEMO_SEED_ADVISORY_LOCK_KEY);
        }
    }
}
