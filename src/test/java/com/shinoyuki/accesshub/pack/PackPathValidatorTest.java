package com.shinoyuki.accesshub.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

class PackPathValidatorTest {
    @Test
    void acceptsPortableRelativePathsWithoutChangingThem() {
        String[] validPaths = {
                "mods/sodium-0.5.3.jar",
                "options.txt",
                "config/wok/client.toml",
                "resourcepacks/中文资源包.zip",
                "mods/a b.jar",
                "a/.hidden"
        };

        for (String path : validPaths) {
            assertTrue(PackPathValidator.isValid(path), path);
            assertEquals(path, PackPathValidator.requireValid(path));
        }
    }

    @Test
    void rejectsTraversalAbsoluteAndNonCanonicalPaths() {
        String[] invalidPaths = {
                null, "", "   ", "/mods/a.jar", "C:/mods/a.jar", "C:mods/a.jar",
                "../mods/a.jar", "mods/../a.jar", "mods/./a.jar", "mods//a.jar",
                "mods/a.jar/", "mods\\a.jar", "\\\\server\\share\\a.jar",
                "mods/a\0.jar"
        };

        for (String path : invalidPaths) {
            assertFalse(PackPathValidator.isValid(path), String.valueOf(path));
            assertThrows(IllegalArgumentException.class, () -> PackPathValidator.requireValid(path));
        }
    }

    @Test
    void rejectsWindowsReservedNamesAndInvalidCharactersInEverySegment() {
        String[] invalidPaths = {
                "CON", "con.txt", "con .txt", "mods/PRN.jar", "aux/config.txt", "mods/NUL",
                "mods/COM1.jar", "mods/com9", "LPT1/file", "mods/lpt9.zip",
                "mods/COM¹.jar", "mods/com²", "LPT³/file",
                "CLOCK$", "mods/clock$.cfg", "conin$/input", "mods/CONOUT$.txt",
                "mods/a?.jar", "mods/a*.jar", "mods/a|b.jar", "mods/a:b.jar",
                "mods/a<b.jar", "mods/a>b.jar", "mods/a\"b.jar", "mods/a.jar.",
                "mods/a.jar ", "mods/a\tb.jar", "mods/a\u007fb.jar"
        };

        for (String path : invalidPaths) {
            assertFalse(PackPathValidator.isValid(path), path);
        }
    }

    @Test
    void rejectsClientOwnedTopLevelDirectoriesCaseInsensitively() {
        String[] invalidPaths = {
                ".aurora/modpack-applied.json", ".AURORA/state.json",
                "saves/world/level.dat", "SAVES/world/level.dat",
                "screenshots/2026-08-17.png", "Screenshots/image.png",
                "logs/latest.log", "LOGS/debug.log"
        };

        for (String path : invalidPaths) {
            assertFalse(PackPathValidator.isValid(path), path);
            assertThrows(IllegalArgumentException.class, () -> PackPathValidator.requireValid(path));
        }

        assertEquals("config/logs/client.toml",
                PackPathValidator.requireValid("config/logs/client.toml"));
    }

    @Test
    void rejectsDosShortNameAliasesInEverySegment() {
        String[] invalidPaths = {
                "AURORA~1/state.json", "SCREEN~1/image.png", "mods/MYMOD~1.JAR",
                "config/ABCDEF~123.toml", "mods/A~9", "\uD840\uDC00abc~1/state.json"
        };

        for (String path : invalidPaths) {
            assertFalse(PackPathValidator.isValid(path), path);
        }

        assertEquals("mods/name~preview.jar",
                PackPathValidator.requireValid("mods/name~preview.jar"));
        assertEquals("mods/seventh~1.jar",
                PackPathValidator.requireValid("mods/seventh~1.jar"));
        assertEquals("mods/\uD840\uDC00abcdef~1.jar",
                PackPathValidator.requireValid("mods/\uD840\uDC00abcdef~1.jar"));
    }

    @Test
    void acceptsRandomizedPortablePathsAndRejectsInjectedTraversal() {
        Random random = new Random(0x5eedL);
        for (int i = 0; i < 200; i++) {
            String fileName = "file-" + Long.toUnsignedString(random.nextLong(), 36) + ".jar";
            String safe = "mods/generated/" + fileName;
            assertEquals(safe, PackPathValidator.requireValid(safe));
            assertFalse(PackPathValidator.isValid("mods/../" + fileName));
        }
    }
}
