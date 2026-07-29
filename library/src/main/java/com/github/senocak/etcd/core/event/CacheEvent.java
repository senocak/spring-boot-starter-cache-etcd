package com.github.senocak.etcd.core.event;

import java.time.Instant;

public sealed interface CacheEvent<K, V> permits CacheInsertedEvent, CacheEvictedEvent {
    String getCacheName();
    K getKey();
    V getValue();
    Instant getOccurredAt();
}
