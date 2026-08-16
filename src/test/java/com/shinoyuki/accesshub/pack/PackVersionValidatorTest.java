package com.shinoyuki.accesshub.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PackVersionValidatorTest {
    @Test
    void acceptsExactlyTheLoaderKindsAuroraCanInstallForManagedPacks() {
        for (String loaderKind : new String[] {"fabric", "quilt", "forge", "neoforge"}) {
            assertEquals(loaderKind, PackVersionValidator.requireValidLoaderKind(loaderKind));
        }

        for (String loaderKind : new String[] {null, "", "forg", "Forge", "vanilla", "optifine"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> PackVersionValidator.requireValidLoaderKind(loaderKind));
        }
    }

    @Test
    void rejectsVersionValuesThatCannotBeSafeUrlSegments() {
        assertEquals("2.0.0+hotfix", PackVersionValidator.requireValidVersion("2.0.0+hotfix"));
        assertEquals("1.20.1", PackVersionValidator.requireValidMinecraft("1.20.1"));
        assertEquals("47.4.20", PackVersionValidator.requireValidLoaderVersion("47.4.20"));

        for (String value : new String[] {null, "", "../2.0.0", "2/0/0", " 2.0.0", "2.0.0 "}) {
            assertThrows(IllegalArgumentException.class,
                    () -> PackVersionValidator.requireValidVersion(value));
        }

        String maximum = "v" + "1".repeat(127);
        assertEquals(maximum, PackVersionValidator.requireValidVersion(maximum));
        assertThrows(IllegalArgumentException.class,
                () -> PackVersionValidator.requireValidVersion(maximum + "1"));
    }
}
