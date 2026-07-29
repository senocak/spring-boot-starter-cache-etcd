package com.github.senocak.etcd.core.event;

import java.time.Instant;
import java.util.Objects;

public final class CacheEvictedEvent<K, V> implements CacheEvent<K, V> {
    private final String cacheName;
    private final K key;
    private final V value;
    private final Instant occurredAt;

    public CacheEvictedEvent(String cacheName, K key, V value) {
        this(cacheName, key, value, Instant.now());
    }

    public CacheEvictedEvent(String cacheName, K key, V value, Instant occurredAt) {
        this.cacheName = Objects.requireNonNull(cacheName, "cacheName must not be null");
        this.key = Objects.requireNonNull(key, "key must not be null");
        this.value = Objects.requireNonNull(value, "value must not be null");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    @Override public String getCacheName() { return cacheName; }
    @Override public K getKey() { return key; }
    @Override public V getValue() { return value; }
    @Override public Instant getOccurredAt() { return occurredAt; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CacheEvictedEvent<?, ?> event)) return false;
        return cacheName.equals(event.cacheName) && key.equals(event.key) && value.equals(event.value)
            && occurredAt.equals(event.occurredAt);
    }

    @Override public int hashCode() { return Objects.hash(cacheName, key, value, occurredAt); }

    @Override
    public String toString() {
        return "CacheEvictedEvent[cacheName=" + cacheName + ", key=" + key + ", value=" + value
            + ", occurredAt=" + occurredAt + "]";
    }
}
