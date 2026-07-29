package com.github.senocak.etcd.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.senocak.etcd.core.event.CacheEvictedEvent;
import com.github.senocak.etcd.core.event.CacheInsertedEvent;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;

class EtcdCacheManagerTest {
    @Test
    void reusesCachesAndReportsCacheNames() {
        EtcdCacheManager manager = managerFor(new InMemoryCacheDocumentStore(), Object::toString, null, null);
        Cache users = manager.getCache("users");
        assertSame(users, manager.getCache("users"));
        manager.getCache("products");
        assertEquals(2, manager.getCacheCount());
        assertEquals(Set.of("users", "products"), Set.copyOf(manager.getCacheNames()));
    }

    @Test
    void clearsAllManagedCaches() {
        EtcdCacheManager manager = managerFor(new InMemoryCacheDocumentStore(), Object::toString, null, null);
        Cache users = manager.getCache("users");
        Cache products = manager.getCache("products");
        users.put("1", new CachedUser("1", "ada"));
        products.put("2", new CachedUser("2", "grace"));
        manager.clearAll();
        assertNull(users.get("1"));
        assertNull(products.get("2"));
    }

    @Test
    void usesCustomKeySerializerAndPersistsValues() {
        InMemoryCacheDocumentStore store = new InMemoryCacheDocumentStore();
        Function<Object, String> serializer = key -> ((LookupKey) key).id();
        EtcdCacheManager manager = managerFor(store, serializer, null, null);
        manager.getCache("users").put(new LookupKey("42"), new CachedUser("42", "ada"));

        CachedUser actual = managerFor(store, serializer, null, null)
            .getCache("users").get(new LookupKey("42"), CachedUser.class);
        assertEquals(new CachedUser("42", "ada"), actual);
    }

    @Test
    void publishesManagedCacheEvents() {
        RecordingApplicationEventPublisher publisher = new RecordingApplicationEventPublisher();
        EtcdCacheManager manager = managerFor(
            new InMemoryCacheDocumentStore(), Object::toString, publisher, null);
        Cache cache = manager.getCache("users");
        cache.put("42", new CachedUser("42", "ada"));
        cache.evict("42");
        assertInstanceOf(CacheInsertedEvent.class, publisher.events.get(0));
        assertInstanceOf(CacheEvictedEvent.class, publisher.events.get(1));
    }

    /** The manager owns no expiry scheduler: the TTL just rides along to the store on every write. */
    @Test
    void appliesConfiguredEntryTtlToManagedCacheWrites() {
        InMemoryCacheDocumentStore store = new InMemoryCacheDocumentStore();
        managerFor(store, Object::toString, null, Duration.ofSeconds(30))
            .getCache("users").put("1", new CachedUser("1", "ada"));
        assertEquals(List.of(Duration.ofSeconds(30)), store.recordedTtls);
    }

    private EtcdCacheManager managerFor(CacheDocumentStore store, Function<Object, String> keySerializer,
                                        RecordingApplicationEventPublisher publisher, Duration entryTtl) {
        ObjectMapper objectMapper = EtcdCacheImpl.defaultObjectMapper();
        return new EtcdCacheManager(
            cacheName -> new EtcdCacheImpl<>(cacheName, String.class, SpringCacheEntry.class,
                store, objectMapper, null, entryTtl),
            objectMapper, keySerializer, publisher);
    }

    record CachedUser(String id, String username) { }
    record LookupKey(String id) { }

    static final class InMemoryCacheDocumentStore implements CacheDocumentStore {
        private final Map<String, CacheDocument> documents = new LinkedHashMap<>();
        final List<Duration> recordedTtls = new java.util.ArrayList<>();

        @Override public CacheDocument get(String cacheName, String cacheKey) {
            return documents.get(documentKey(cacheName, cacheKey));
        }
        @Override public void put(CacheDocument document, Duration ttl) {
            recordedTtls.add(ttl);
            documents.put(documentKey(document.getCacheName(), document.getCacheKey()), document);
        }
        @Override public CacheDocument delete(String cacheName, String cacheKey) {
            return documents.remove(documentKey(cacheName, cacheKey));
        }
        @Override public List<CacheDocument> findAll(String cacheName) {
            return documents.values().stream().filter(document -> document.getCacheName().equals(cacheName)).toList();
        }
        private String documentKey(String cacheName, String cacheKey) { return cacheName + "::" + cacheKey; }
    }
}
