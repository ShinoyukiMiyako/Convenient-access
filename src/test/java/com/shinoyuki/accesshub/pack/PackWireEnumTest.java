package com.shinoyuki.accesshub.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;

class PackWireEnumTest {
    private final Gson gson = new Gson();

    @Test
    void serializesSpecificationValuesInLowercase() {
        assertEquals("\"draft\"", gson.toJson(PackVersionStatus.DRAFT));
        assertEquals("\"published\"", gson.toJson(PackVersionStatus.PUBLISHED));
        assertEquals("\"archived\"", gson.toJson(PackVersionStatus.ARCHIVED));
        assertEquals("\"platform\"", gson.toJson(PackEntryKind.PLATFORM));
        assertEquals("\"custom\"", gson.toJson(PackEntryKind.CUSTOM));
        assertEquals("\"managed\"", gson.toJson(PackEntryPolicy.MANAGED));
        assertEquals("\"seeded\"", gson.toJson(PackEntryPolicy.SEEDED));
        assertEquals("\"optional\"", gson.toJson(PackEntryPolicy.OPTIONAL));
    }

    @Test
    void deserializesOnlyDeclaredLowercaseValues() {
        assertEquals(PackVersionStatus.PUBLISHED,
                gson.fromJson("\"published\"", PackVersionStatus.class));
        assertEquals(PackEntryKind.CUSTOM, gson.fromJson("\"custom\"", PackEntryKind.class));
        assertEquals(PackEntryPolicy.SEEDED,
                gson.fromJson("\"seeded\"", PackEntryPolicy.class));

        assertNull(gson.fromJson("\"PUBLISHED\"", PackVersionStatus.class));
        assertNull(gson.fromJson("\"CUSTOM\"", PackEntryKind.class));
        assertNull(gson.fromJson("\"MANAGED\"", PackEntryPolicy.class));
        assertNull(gson.fromJson("\"unknown\"", PackEntryPolicy.class));
    }
}
