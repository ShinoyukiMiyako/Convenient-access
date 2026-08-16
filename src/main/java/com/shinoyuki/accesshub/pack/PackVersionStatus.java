package com.shinoyuki.accesshub.pack;

import com.google.gson.annotations.SerializedName;
import com.google.gson.annotations.JsonAdapter;

/** 整合包版本生命周期状态。 */
@JsonAdapter(PackVersionStatus.WireAdapter.class)
public enum PackVersionStatus {
    @SerializedName("draft")
    DRAFT("draft"),
    @SerializedName("published")
    PUBLISHED("published"),
    @SerializedName("archived")
    ARCHIVED("archived");

    private final String databaseValue;

    PackVersionStatus(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    public String databaseValue() {
        return databaseValue;
    }

    public static PackVersionStatus fromDatabase(String value) {
        for (PackVersionStatus status : values()) {
            if (status.databaseValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的整合包版本状态: " + value);
    }

    public static final class WireAdapter extends PackEnumJsonAdapter<PackVersionStatus> {
        public WireAdapter() {
            super(values(), PackVersionStatus::databaseValue);
        }
    }
}
