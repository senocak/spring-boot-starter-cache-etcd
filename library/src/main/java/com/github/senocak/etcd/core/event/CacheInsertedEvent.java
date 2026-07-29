package com.github.senocak.etcd.core.event;

import java.time.Instant;
import java.util.Objects;

public final class CacheInsertedEvent<K, V> implements CacheEvent<K, V> {
    private final String cacheName;
    private final K key;
    private final V value;
    private final V previousValue;
    private final Instant occurredAt;

    public CacheInsertedEvent(String cacheName, K key, V value) {
        this(cacheName, key, value, null, Instant.now());
    }

    public CacheInsertedEvent(String cacheName, K key, V value, V previousValue) {
        this(cacheName, key, value, previousValue, Instant.now());
    }

    public CacheInsertedEvent(String cacheName, K key, V value, V previousValue, Instant occurredAt) {
        this.cacheName = Objects.requireNonNull(cacheName, "cacheName must not be null");
        this.key = Objects.requireNonNull(key, "key must not be null");
        this.value = Objects.requireNonNull(value, "value must not be null");
        this.previousValue = previousValue;
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    @Override public String getCacheName() { return cacheName; }
    @Override public K getKey() { return key; }
    @Override public V getValue() { return value; }
    public V getPreviousValue() { return previousValue; }
    @Override public Instant getOccurredAt() { return occurredAt; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CacheInsertedEvent<?, ?> event)) return false;
        return cacheName.equals(event.cacheName) && key.equals(event.key) && value.equals(event.value)
            && Objects.equals(previousValue, event.previousValue) && occurredAt.equals(event.occurredAt);
    }

    @Override public int hashCode() { return Objects.hash(cacheName, key, value, previousValue, occurredAt); }

    @Override
    public String toString() {
        return "CacheInsertedEvent[cacheName=" + cacheName + ", key=" + key + ", value=" + value
            + ", previousValue=" + previousValue + ", occurredAt=" + occurredAt + "]";
    }
}
