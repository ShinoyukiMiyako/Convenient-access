package com.shinoyuki.accesshub.pack;

import java.util.Locale;
import java.util.Set;

/** Validates manifest paths before they cross the instance-root boundary. */
public final class PackPathValidator {
    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL", "CLOCK$", "CONIN$", "CONOUT$",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
            "COM¹", "COM²", "COM³", "LPT¹", "LPT²", "LPT³");
    private static final Set<String> PROTECTED_TOP_LEVEL_DIRECTORIES = Set.of(
            ".aurora", "saves", "screenshots", "logs");
    private static final String WINDOWS_FORBIDDEN_CHARACTERS = "<>:\"|?*";

    private PackPathValidator() {
    }

    public static boolean isValid(String path) {
        try {
            requireValid(path);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static String requireValid(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("整合包文件路径不能为空");
        }
        if (path.startsWith("/") || path.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("整合包文件路径必须是使用正斜杠的相对路径: " + path);
        }
        if (path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("整合包文件路径不能包含 NUL 字符");
        }

        String[] segments = path.split("/", -1);
        if (PROTECTED_TOP_LEVEL_DIRECTORIES.contains(segments[0].toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("整合包文件路径进入了客户端保护目录: " + path);
        }
        for (String segment : segments) {
            requireValidSegment(path, segment);
        }
        return path;
    }

    private static void requireValidSegment(String path, String segment) {
        if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
            throw new IllegalArgumentException("整合包文件路径包含空段或目录穿越: " + path);
        }
        if (segment.endsWith(" ") || segment.endsWith(".")) {
            throw new IllegalArgumentException("整合包文件路径包含 Windows 不可表示的尾字符: " + path);
        }
        if (isDosShortName(segment)) {
            throw new IllegalArgumentException("整合包文件路径包含 DOS 短文件名别名: " + path);
        }

        for (int i = 0; i < segment.length(); i++) {
            char character = segment.charAt(i);
            if (character < 0x20 || character == 0x7f
                    || WINDOWS_FORBIDDEN_CHARACTERS.indexOf(character) >= 0) {
                throw new IllegalArgumentException("整合包文件路径包含非法字符: " + path);
            }
        }

        int extensionSeparator = segment.indexOf('.');
        String deviceName = extensionSeparator < 0 ? segment : segment.substring(0, extensionSeparator);
        deviceName = deviceName.stripTrailing();
        if (WINDOWS_RESERVED_NAMES.contains(deviceName.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("整合包文件路径包含 Windows 保留名: " + path);
        }
    }

    private static boolean isDosShortName(String segment) {
        int extensionSeparator = segment.indexOf('.');
        String basename = extensionSeparator < 0 ? segment : segment.substring(0, extensionSeparator);
        int tilde = basename.lastIndexOf('~');
        if (tilde <= 0 || tilde == basename.length() - 1 || basename.indexOf('~') != tilde) {
            return false;
        }
        int prefixLength = basename.codePointCount(0, tilde);
        if (prefixLength > 6) {
            return false;
        }
        for (int index = tilde + 1; index < basename.length(); index++) {
            char character = basename.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }
}
