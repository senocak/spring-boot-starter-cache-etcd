package com.github.senocak.etcd.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

public final class SpringCacheEntry {
    private final String type;
    private final JsonNode value;

    @JsonCreator
    public SpringCacheEntry(@JsonProperty("type") String type, @JsonProperty("value") JsonNode value) {
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.value = Objects.requireNonNull(value, "value must not be null");
    }

    public String getType() { return type; }
    public JsonNode getValue() { return value; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SpringCacheEntry entry)) return false;
        return type.equals(entry.type) && value.equals(entry.value);
    }

    @Override public int hashCode() { return Objects.hash(type, value); }
}
