package com.github.senocak.etcd.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.senocak.etcd.core.event.CacheEvent;
import com.github.senocak.etcd.core.event.CacheEvictedEvent;
import com.github.senocak.etcd.core.event.CacheInsertedEvent;
import io.etcd.jetcd.Client;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.context.ApplicationEventPublisher;

/**
 * etcd-backed cache that persists every entry under its own key.
 *
 * <p>Entry expiry is handled by etcd itself: {@code entryTtl} is passed to the store, which
 * attaches a lease to each written key. Nothing in this class inspects expiry, and no eviction
 * event is published when etcd reclaims an expired key.
 */
public final class EtcdCacheImpl<K, V> implements EtcdCache<K, V> {
    public static final String DEFAULT_KEY_PREFIX = "/spring_cache_entries";

    private final String cacheName;
    private final Class<K> keyType;
    private final Class<V> valueType;
    private final CacheDocumentStore cacheStore;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final Duration entryTtl;
    private final ReentrantLock ioLock = new ReentrantLock();

    public EtcdCacheImpl(String cacheName, Class<K> keyType, Class<V> valueType, Client etcdClient) {
        this(cacheName, keyType, valueType, etcdClient, DEFAULT_KEY_PREFIX,
            defaultObjectMapper(), null, null);
    }

    public EtcdCacheImpl(String cacheName, Class<K> keyType, Class<V> valueType,
                         Client etcdClient, String keyPrefix) {
        this(cacheName, keyType, valueType, etcdClient, keyPrefix, defaultObjectMapper(), null, null);
    }

    public EtcdCacheImpl(String cacheName, Class<K> keyType, Class<V> valueType,
                         Client etcdClient, String keyPrefix, ObjectMapper objectMapper,
                         ApplicationEventPublisher applicationEventPublisher, Duration entryTtl) {
        this(cacheName, keyType, valueType,
            new EtcdCacheDocumentStore(etcdClient, keyPrefix, objectMapper,
                EtcdCacheDocumentStore.DEFAULT_REQUEST_TIMEOUT),
            objectMapper, applicationEventPublisher, entryTtl);
    }

    public EtcdCacheImpl(String cacheName, Class<K> keyType, Class<V> valueType,
                         CacheDocumentStore cacheStore) {
        this(cacheName, keyType, valueType, cacheStore, defaultObjectMapper(), null, null);
    }

    public EtcdCacheImpl(String cacheName, Class<K> keyType, Class<V> valueType,
                         CacheDocumentStore cacheStore, ObjectMapper objectMapper) {
        this(cacheName, keyType, valueType, cacheStore, objectMapper, null, null);
    }

    public EtcdCacheImpl(String cacheName, Class<K> keyType, Class<V> valueType,
                         CacheDocumentStore cacheStore, ObjectMapper objectMapper,
                         ApplicationEventPublisher applicationEventPublisher, Duration entryTtl) {
        if (entryTtl != null && entryTtl.isNegative()) {
            throw new IllegalArgumentException("Entry TTL must not be negative");
        }
        this.cacheName = sanitizeCacheName(cacheName);
        this.keyType = keyType;
        this.valueType = valueType;
        this.cacheStore = cacheStore;
        this.objectMapper = objectMapper;
        this.applicationEventPublisher = applicationEventPublisher;
        this.entryTtl = entryTtl;
    }

    @Override
    public V get(K key) {
        ioLock.lock();
        try {
            CacheDocument document = cacheStore.get(cacheName, toStoredKey(key));
            return document == null ? null : fromStoredValue(document.getValueJson());
        } finally {
            ioLock.unlock();
        }
    }

    @Override
    public void put(K key, V value) {
        CacheInsertedEvent<K, V> event;
        ioLock.lock();
        try {
            String storedKey = toStoredKey(key);
            CacheDocument previousDocument = cacheStore.get(cacheName, storedKey);
            V previousValue = previousDocument == null ? null : fromStoredValue(previousDocument.getValueJson());
            cacheStore.put(new CacheDocument(
                cacheName,
                storedKey,
                storedKey,
                toStoredValue(value)
            ), entryTtl);
            event = new CacheInsertedEvent<>(cacheName, key, value, previousValue);
        } finally {
            ioLock.unlock();
        }
        fireEvent(event);
    }

    @Override
    public V evict(K key) {
        CacheEvictedEvent<K, V> event;
        ioLock.lock();
        try {
            CacheDocument removedDocument = cacheStore.delete(cacheName, toStoredKey(key));
            if (removedDocument == null) {
                return null;
            }
            event = new CacheEvictedEvent<>(cacheName, key, fromStoredValue(removedDocument.getValueJson()));
        } finally {
            ioLock.unlock();
        }
        fireEvent(event);
        return event.getValue();
    }

    @Override
    public void clear() {
        List<CacheEvictedEvent<K, V>> events = new ArrayList<>();
        ioLock.lock();
        try {
            for (CacheDocument document : cacheStore.findAll(cacheName)) {
                CacheDocument removedDocument = cacheStore.delete(cacheName, document.getCacheKey());
                if (removedDocument != null) {
                    events.add(new CacheEvictedEvent<>(
                        cacheName,
                        fromStoredKey(removedDocument.getKeyJson()),
                        fromStoredValue(removedDocument.getValueJson())
                    ));
                }
            }
        } finally {
            ioLock.unlock();
        }
        events.forEach(this::fireEvent);
    }

    @Override
    public boolean containsKey(K key) {
        ioLock.lock();
        try {
            return cacheStore.get(cacheName, toStoredKey(key)) != null;
        } finally {
            ioLock.unlock();
        }
    }

    @Override
    public int size() {
        ioLock.lock();
        try {
            return cacheStore.findAll(cacheName).size();
        } finally {
            ioLock.unlock();
        }
    }

    @Override
    public Set<K> keys() {
        ioLock.lock();
        try {
            LinkedHashSet<K> keys = new LinkedHashSet<>();
            cacheStore.findAll(cacheName).stream()
                .sorted(Comparator.comparing(CacheDocument::getCacheKey))
                .map(document -> fromStoredKey(document.getKeyJson()))
                .forEach(keys::add);
            return keys;
        } finally {
            ioLock.unlock();
        }
    }

    public static ObjectMapper defaultObjectMapper() {
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private String toStoredKey(K key) {
        return writeValue(key);
    }

    private K fromStoredKey(String keyJson) {
        return readValue(keyJson, keyType);
    }

    private String toStoredValue(V value) {
        return writeValue(value);
    }

    private V fromStoredValue(String valueJson) {
        return readValue(valueJson, valueType);
    }

    private String writeValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize cache value", exception);
        }
    }

    private <T> T readValue(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize cache value", exception);
        }
    }

    private void fireEvent(CacheEvent<K, V> event) {
        if (applicationEventPublisher != null) {
            applicationEventPublisher.publishEvent(event);
        }
    }

    private static String sanitizeCacheName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalArgumentException("Cache name must not be blank");
        }
        boolean valid = candidate.chars().allMatch(character ->
            Character.isLetterOrDigit(character) || character == '-' || character == '_' || character == '.');
        if (!valid) {
            throw new IllegalArgumentException("Cache name may only contain letters, digits, dots, dashes, and underscores");
        }
        return candidate;
    }
}
