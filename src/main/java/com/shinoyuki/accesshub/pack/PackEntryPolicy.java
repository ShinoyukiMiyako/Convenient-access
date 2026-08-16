package com.shinoyuki.accesshub.pack;

import com.google.gson.annotations.SerializedName;
import com.google.gson.annotations.JsonAdapter;

/** 客户端同步文件时采用的策略。 */
@JsonAdapter(PackEntryPolicy.WireAdapter.class)
public enum PackEntryPolicy {
    @SerializedName("managed")
    MANAGED("managed"),
    @SerializedName("seeded")
    SEEDED("seeded"),
    @SerializedName("optional")
    OPTIONAL("optional");

    private final String databaseValue;

    PackEntryPolicy(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    public String databaseValue() {
        return databaseValue;
    }

    public static PackEntryPolicy fromDatabase(String value) {
        for (PackEntryPolicy policy : values()) {
            if (policy.databaseValue.equals(value)) {
                return policy;
            }
        }
        throw new IllegalArgumentException("未知的整合包同步策略: " + value);
    }

    public static final class WireAdapter extends PackEnumJsonAdapter<PackEntryPolicy> {
        public WireAdapter() {
            super(values(), PackEntryPolicy::databaseValue);
        }
    }
}
