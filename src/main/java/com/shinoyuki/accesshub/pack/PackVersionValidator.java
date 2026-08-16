package com.shinoyuki.accesshub.pack;

import java.util.Set;
import java.util.regex.Pattern;

/** Validation shared by pack version write paths. */
public final class PackVersionValidator {
    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z._+-]{0,127}");
    private static final Set<String> SUPPORTED_LOADER_KINDS = Set.of(
            "fabric", "quilt", "forge", "neoforge");

    private PackVersionValidator() {
    }

    public static String requireValidVersion(String version) {
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("整合包版本号格式非法");
        }
        return version;
    }

    public static String requireValidMinecraft(String minecraft) {
        if (minecraft == null || !VERSION.matcher(minecraft).matches()) {
            throw new IllegalArgumentException("Minecraft 版本号格式非法");
        }
        return minecraft;
    }

    public static String requireValidLoaderKind(String loaderKind) {
        if (loaderKind == null || !SUPPORTED_LOADER_KINDS.contains(loaderKind)) {
            throw new IllegalArgumentException(
                    "加载器类型仅支持 fabric、quilt、forge 或 neoforge");
        }
        return loaderKind;
    }

    public static String requireValidLoaderVersion(String loaderVersion) {
        if (loaderVersion == null || !VERSION.matcher(loaderVersion).matches()) {
            throw new IllegalArgumentException("加载器版本号格式非法");
        }
        return loaderVersion;
    }

    public static String requireValidNote(String note) {
        if (note != null && note.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("更新说明不能包含 NUL 字符");
        }
        return note;
    }
}
