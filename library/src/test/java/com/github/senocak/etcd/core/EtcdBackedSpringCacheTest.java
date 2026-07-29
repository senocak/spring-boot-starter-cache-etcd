package com.github.senocak.etcd.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.senocak.etcd.core.event.CacheEvictedEvent;
import com.github.senocak.etcd.core.event.CacheInsertedEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;

class EtcdBackedSpringCacheTest {
    @Test
    void reloadsTypedValuesFromManagedCaches() {
        Map<String, InMemoryEtcdCache> backingCaches = new LinkedHashMap<>();
        Cache cache = managerUsing(backingCaches).getCache("users");
        cache.put("42", new CachedUser("42", "ada"));

        Object value = managerUsing(backingCaches).getCache("users").get("42").get();
        assertInstanceOf(CachedUser.class, value);
        assertEquals(new CachedUser("42", "ada"), value);
    }

    @Test
    void usesLoaderOnlyWhenKeyIsMissing() {
        Cache cache = managerUsing(new LinkedHashMap<>()).getCache("users");
        CachedUser loaded = cache.get("42", () -> new CachedUser("42", "ada"));
        assertEquals(new CachedUser("42", "ada"), loaded);
        assertEquals(loaded, cache.get("42", CachedUser.class));
        assertEquals(loaded, cache.get("42", () -> { throw new IllegalStateException("not called"); }));
    }

    @Test
    void returnsWrappedTypedAndUntypedValues() {
        InMemoryEtcdCache backingCache = new InMemoryEtcdCache();
        EtcdBackedSpringCache cache = new EtcdBackedSpringCache(
            "users", backingCache, EtcdCacheImpl.defaultObjectMapper());
        CachedUser user = new CachedUser("42", "ada");
        cache.put("42", user);

        assertEquals("users", cache.getName());
        assertSame(backingCache, cache.getNativeCache());
        assertEquals(user, cache.get("42").get());
        assertEquals(user, cache.get("42", CachedUser.class));
        assertEquals(user, cache.get("42", (Class<Object>) null));
        assertNull(cache.get("42", String.class));
        assertNull(cache.get("missing"));
    }

    @Test
    void doesNotCacheNullValues() {
        InMemoryEtcdCache backingCache = new InMemoryEtcdCache();
        EtcdBackedSpringCache cache = new EtcdBackedSpringCache(
            "users", backingCache, EtcdCacheImpl.defaultObjectMapper());
        cache.put("42", null);
        assertFalse(backingCache.containsKey("42"));
        assertNull(cache.get("42"));
    }

    @Test
    void wrapsLoaderExceptions() {
        EtcdBackedSpringCache cache = new EtcdBackedSpringCache(
            "users", new InMemoryEtcdCache(), EtcdCacheImpl.defaultObjectMapper());
        Cache.ValueRetrievalException exception = assertThrows(Cache.ValueRetrievalException.class,
            () -> cache.get("42", () -> { throw new IllegalStateException("boom"); }));
        assertInstanceOf(IllegalStateException.class, exception.getCause());
        assertEquals("boom", exception.getCause().getMessage());
    }

    @Test
    void evictsClearsAndInvalidatesEntries() {
        InMemoryEtcdCache backingCache = new InMemoryEtcdCache();
        EtcdBackedSpringCache cache = new EtcdBackedSpringCache(
            "users", backingCache, EtcdCacheImpl.defaultObjectMapper());
        cache.put("42", new CachedUser("42", "ada"));
        cache.evict("42");
        assertNull(cache.get("42"));
        cache.put("43", new CachedUser("43", "grace"));
        cache.clear();
        assertEquals(0, backingCache.size());
        cache.put("44", new CachedUser("44", "katherine"));
        assertTrue(cache.invalidate());
        assertEquals(0, backingCache.size());
    }

    @Test
    void publishesDecodedSpringCacheEvents() {
        RecordingApplicationEventPublisher publisher = new RecordingApplicationEventPublisher();
        EtcdBackedSpringCache cache = new EtcdBackedSpringCache(
            "users", new InMemoryEtcdCache(), EtcdCacheImpl.defaultObjectMapper(),
            Object::toString, publisher);
        cache.put("42", new CachedUser("42", "ada"));
        cache.put("42", new CachedUser("42", "grace"));
        cache.evict("42");

        CacheInsertedEvent<?, ?> inserted = assertInstanceOf(CacheInsertedEvent.class, publisher.events.get(0));
        assertEquals(new CachedUser("42", "ada"), inserted.getValue());
        assertNull(inserted.getPreviousValue());
        CacheInsertedEvent<?, ?> updated = assertInstanceOf(CacheInsertedEvent.class, publisher.events.get(1));
        assertEquals(new CachedUser("42", "ada"), updated.getPreviousValue());
        CacheEvictedEvent<?, ?> evicted = assertInstanceOf(CacheEvictedEvent.class, publisher.events.get(2));
        assertEquals(new CachedUser("42", "grace"), evicted.getValue());
    }

    private EtcdCacheManager managerUsing(Map<String, InMemoryEtcdCache> backingCaches) {
        return new EtcdCacheManager(
            cacheName -> backingCaches.computeIfAbsent(cacheName, ignored -> new InMemoryEtcdCache()),
            EtcdCacheImpl.defaultObjectMapper());
    }

    record CachedUser(String id, String username) { }

    static final class InMemoryEtcdCache implements EtcdCache<String, SpringCacheEntry> {
        private final Map<String, SpringCacheEntry> entries = new LinkedHashMap<>();
        @Override public SpringCacheEntry get(String key) { return entries.get(key); }
        @Override public void put(String key, SpringCacheEntry value) { entries.put(key, value); }
        @Override public SpringCacheEntry evict(String key) { return entries.remove(key); }
        @Override public void clear() { entries.clear(); }
        @Override public boolean containsKey(String key) { return entries.containsKey(key); }
        @Override public int size() { return entries.size(); }
        @Override public Set<String> keys() { return entries.keySet(); }
    }
}
