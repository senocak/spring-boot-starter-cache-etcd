package com.github.senocak.etcd.core;

import java.time.Duration;
import java.util.List;

public interface CacheDocumentStore {
    CacheDocument get(String cacheName, String cacheKey);

    /**
     * Stores the document, expiring it after {@code ttl}.
     * A null, zero, or negative TTL stores the document without any expiry.
     */
    void put(CacheDocument document, Duration ttl);

    CacheDocument delete(String cacheName, String cacheKey);
    List<CacheDocument> findAll(String cacheName);
}
