package com.stockguard.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates that the modular monolith package structure conforms to the
 * domain modules defined in the system architecture documentation:
 * <ul>
 *   <li>inventory</li>
 *   <li>order</li>
 *   <li>reservation</li>
 *   <li>warehouse</li>
 *   <li>product</li>
 *   <li>organization</li>
 *   <li>audit</li>
 *   <li>shared</li>
 * </ul>
 */
class PackageStructureTest {

    private static final List<String> EXPECTED_MODULES = List.of(
            "inventory",
            "order",
            "reservation",
            "warehouse",
            "product",
            "organization",
            "audit",
            "shared"
    );

    @Test
    @DisplayName("All 8 documented architecture domain modules exist under com.stockguard")
    void allDocumentedModulesExist() {
        ClassLoader classLoader = getClass().getClassLoader();

        for (String moduleName : EXPECTED_MODULES) {
            String fullPackageName = "com.stockguard." + moduleName;
            Package pkg = classLoader.getDefinedPackage(fullPackageName);

            // If getDefinedPackage returns null (prior to class loading), verify via package-info class loading
            if (pkg == null) {
                try {
                    Class<?> packageInfoClass = Class.forName(fullPackageName + ".package-info", false, classLoader);
                    assertNotNull(packageInfoClass, "package-info class should be loadable for " + fullPackageName);
                    pkg = packageInfoClass.getPackage();
                } catch (ClassNotFoundException e) {
                    // Try to verify resource directory existence
                    String path = "com/stockguard/" + moduleName;
                    assertNotNull(classLoader.getResource(path),
                            "Expected module resource directory to exist: " + path);
                }
            }

            if (pkg != null) {
                assertTrue(pkg.getName().startsWith("com.stockguard."),
                        "Package " + pkg.getName() + " should be under com.stockguard root");
            }
        }
    }
}
