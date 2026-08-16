package com.shinoyuki.accesshub.pack;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

/** Strict adapter that never accepts Java enum constant names as wire values. */
abstract class PackEnumJsonAdapter<E extends Enum<E>> extends TypeAdapter<E> {
    private final Map<String, E> valuesByWireName;
    private final Function<E, String> wireName;

    protected PackEnumJsonAdapter(E[] values, Function<E, String> wireName) {
        this.wireName = wireName;
        this.valuesByWireName = new HashMap<>();
        for (E value : values) {
            valuesByWireName.put(wireName.apply(value), value);
        }
    }

    @Override
    public void write(JsonWriter out, E value) throws IOException {
        if (value == null) {
            out.nullValue();
        } else {
            out.value(wireName.apply(value));
        }
    }

    @Override
    public E read(JsonReader in) throws IOException {
        if (in.peek() == JsonToken.NULL) {
            in.nextNull();
            return null;
        }
        return valuesByWireName.get(in.nextString());
    }
}
