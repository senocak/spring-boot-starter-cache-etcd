package com.github.senocak.etcd.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

public final class CacheDocument {
    private final String cacheName;
    private final String cacheKey;
    private final String keyJson;
    private final String valueJson;

    @JsonCreator
    public CacheDocument(@JsonProperty("cacheName") String cacheName,
                         @JsonProperty("cacheKey") String cacheKey,
                         @JsonProperty("keyJson") String keyJson,
                         @JsonProperty("valueJson") String valueJson) {
        this.cacheName = Objects.requireNonNull(cacheName, "cacheName must not be null");
        this.cacheKey = Objects.requireNonNull(cacheKey, "cacheKey must not be null");
        this.keyJson = Objects.requireNonNull(keyJson, "keyJson must not be null");
        this.valueJson = Objects.requireNonNull(valueJson, "valueJson must not be null");
    }

    public String getCacheName() { return cacheName; }
    public String getCacheKey() { return cacheKey; }
    public String getKeyJson() { return keyJson; }
    public String getValueJson() { return valueJson; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CacheDocument document)) return false;
        return cacheName.equals(document.cacheName) && cacheKey.equals(document.cacheKey)
            && keyJson.equals(document.keyJson) && valueJson.equals(document.valueJson);
    }

    @Override public int hashCode() { return Objects.hash(cacheName, cacheKey, keyJson, valueJson); }
}
