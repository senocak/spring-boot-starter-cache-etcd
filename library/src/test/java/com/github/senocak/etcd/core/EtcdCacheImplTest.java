package com.github.senocak.etcd.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.senocak.etcd.core.event.CacheEvictedEvent;
import com.github.senocak.etcd.core.event.CacheInsertedEvent;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EtcdCacheImplTest {
    @Test
    void persistsAndReloadsValues() {
        InMemoryCacheDocumentStore store = new InMemoryCacheDocumentStore();
        EtcdCacheImpl<String, CachedUser> cache = newUserCache(store, null, null);

        cache.put("1", new CachedUser("1", "ada"));
        EtcdCacheImpl<String, CachedUser> reloaded = newUserCache(store, null, null);

        assertEquals(new CachedUser("1", "ada"), reloaded.get("1"));
        assertTrue(reloaded.containsKey("1"));
        assertEquals(Set.of("1"), reloaded.keys());
    }

    @Test
    void firesInsertedAndEvictedEvents() {
        RecordingApplicationEventPublisher publisher = new RecordingApplicationEventPublisher();
        EtcdCacheImpl<String, CachedUser> cache =
            newUserCache(new InMemoryCacheDocumentStore(), publisher, null);

        cache.put("1", new CachedUser("1", "ada"));
        cache.put("1", new CachedUser("1", "grace"));
        cache.evict("1");

        CacheInsertedEvent<?, ?> inserted = assertInstanceOf(CacheInsertedEvent.class, publisher.events.get(0));
        assertEquals("users", inserted.getCacheName());
        assertEquals("1", inserted.getKey());
        assertEquals(new CachedUser("1", "ada"), inserted.getValue());
        assertNull(inserted.getPreviousValue());

        CacheInsertedEvent<?, ?> updated = assertInstanceOf(CacheInsertedEvent.class, publisher.events.get(1));
        assertEquals(new CachedUser("1", "grace"), updated.getValue());
        assertEquals(new CachedUser("1", "ada"), updated.getPreviousValue());

        CacheEvictedEvent<?, ?> evicted = assertInstanceOf(CacheEvictedEvent.class, publisher.events.get(2));
        assertEquals("1", evicted.getKey());
        assertEquals(new CachedUser("1", "grace"), evicted.getValue());
    }

    @Test
    void clearsEveryPersistedEntryAndPublishesEvents() {
        RecordingApplicationEventPublisher publisher = new RecordingApplicationEventPublisher();
        InMemoryCacheDocumentStore store = new InMemoryCacheDocumentStore();
        EtcdCacheImpl<String, CachedUser> cache = newUserCache(store, publisher, null);
        cache.put("1", new CachedUser("1", "ada"));
        cache.put("2", new CachedUser("2", "grace"));
        publisher.events.clear();

        cache.clear();

        assertEquals(2, publisher.events.size());
        assertEquals(0, newUserCache(store, null, null).size());
        assertTrue(publisher.events.stream().allMatch(CacheEvictedEvent.class::isInstance));
    }

    @Test
    void readsCurrentValuesFromStoreForEveryGet() {
        InMemoryCacheDocumentStore store = new InMemoryCacheDocumentStore();
        EtcdCacheImpl<String, CachedUser> first = newUserCache(store, null, null);
        EtcdCacheImpl<String, CachedUser> second = newUserCache(store, null, null);
        first.put("1", new CachedUser("1", "ada"));
        second.put("1", new CachedUser("1", "grace"));
        assertEquals(new CachedUser("1", "grace"), first.get("1"));
    }

    @Test
    void validatesCacheNames() {
        assertThrows(IllegalArgumentException.class,
            () -> new EtcdCacheImpl<>(" ", String.class, CachedUser.class, new InMemoryCacheDocumentStore()));
        assertThrows(IllegalArgumentException.class,
            () -> new EtcdCacheImpl<>("users/active", String.class, CachedUser.class,
                new InMemoryCacheDocumentStore()));

        EtcdCacheImpl<String, CachedUser> cache = new EtcdCacheImpl<>(
            "users-v1.active_cache", String.class, CachedUser.class, new InMemoryCacheDocumentStore());
        cache.put("1", new CachedUser("1", "ada"));
        assertEquals(new CachedUser("1", "ada"), cache.get("1"));
    }

    @Test
    void rejectsNegativeEntryTtl() {
        assertThrows(IllegalArgumentException.class,
            () -> newUserCache(new InMemoryCacheDocumentStore(), null, Duration.ofSeconds(-1)));
    }

    @Test
    void handlesMissingKeysAndEmptyStores() {
        EtcdCacheImpl<String, CachedUser> cache = newUserCache(new InMemoryCacheDocumentStore(), null, null);
        assertEquals(0, cache.size());
        assertEquals(Set.of(), cache.keys());
        cache.put("1", new CachedUser("1", "ada"));
        assertNull(cache.evict("missing"));
        assertEquals(1, cache.size());
    }

    /** Expiry itself belongs to etcd; the cache only has to hand its TTL to the store on every write. */
    @Test
    void forwardsEntryTtlToStoreOnEveryWrite() {
        InMemoryCacheDocumentStore withTtl = new InMemoryCacheDocumentStore();
        newUserCache(withTtl, null, Duration.ofSeconds(30)).put("1", new CachedUser("1", "ada"));
        assertEquals(List.of(Duration.ofSeconds(30)), withTtl.recordedTtls);

        InMemoryCacheDocumentStore withoutTtl = new InMemoryCacheDocumentStore();
        newUserCache(withoutTtl, null, null).put("1", new CachedUser("1", "ada"));
        assertEquals(1, withoutTtl.recordedTtls.size());
        assertNull(withoutTtl.recordedTtls.getFirst());
    }

    @Test
    void persistsJavaTimeValues() {
        InMemoryCacheDocumentStore store = new InMemoryCacheDocumentStore();
        EtcdCacheImpl<String, CachedEvent> cache =
            new EtcdCacheImpl<>("events", String.class, CachedEvent.class, store);
        CachedEvent event = new CachedEvent("1", LocalDate.of(2026, 6, 10));
        cache.put("1", event);
        assertEquals(event, new EtcdCacheImpl<>("events", String.class, CachedEvent.class, store).get("1"));
    }

    private EtcdCacheImpl<String, CachedUser> newUserCache(
        CacheDocumentStore store, RecordingApplicationEventPublisher publisher, Duration entryTtl
    ) {
        return new EtcdCacheImpl<>("users", String.class, CachedUser.class, store,
            EtcdCacheImpl.defaultObjectMapper(), publisher, entryTtl);
    }

    record CachedUser(String id, String username) { }
    record CachedEvent(String id, LocalDate happenedOn) { }

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
