package com.shinoyuki.accesshub.pack;

import com.google.gson.annotations.SerializedName;
import com.google.gson.annotations.JsonAdapter;

/** 整合包文件来源类型。 */
@JsonAdapter(PackEntryKind.WireAdapter.class)
public enum PackEntryKind {
    @SerializedName("platform")
    PLATFORM("platform"),
    @SerializedName("custom")
    CUSTOM("custom");

    private final String databaseValue;

    PackEntryKind(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    public String databaseValue() {
        return databaseValue;
    }

    public static PackEntryKind fromDatabase(String value) {
        for (PackEntryKind kind : values()) {
            if (kind.databaseValue.equals(value)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("未知的整合包条目类型: " + value);
    }

    public static final class WireAdapter extends PackEnumJsonAdapter<PackEntryKind> {
        public WireAdapter() {
            super(values(), PackEntryKind::databaseValue);
        }
    }
}
