package com.github.senocak.etcd.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.senocak.etcd.core.event.CacheEvictedEvent;
import com.github.senocak.etcd.core.event.CacheInsertedEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.context.ApplicationEventPublisher;

/** Spring Cache adapter backed by a typed persistent cache. */
public final class EtcdBackedSpringCache implements Cache {
    private final String name;
    private final EtcdCache<String, SpringCacheEntry> cache;
    private final ObjectMapper objectMapper;
    private final Function<Object, String> keySerializer;
    private final ApplicationEventPublisher applicationEventPublisher;

    public EtcdBackedSpringCache(String name,
                                 EtcdCache<String, SpringCacheEntry> cache,
                                 ObjectMapper objectMapper) {
        this(name, cache, objectMapper, Object::toString, null);
    }

    public EtcdBackedSpringCache(String name,
                                 EtcdCache<String, SpringCacheEntry> cache,
                                 ObjectMapper objectMapper,
                                 Function<Object, String> keySerializer,
                                 ApplicationEventPublisher applicationEventPublisher) {
        this.name = name;
        this.cache = cache;
        this.objectMapper = objectMapper;
        this.keySerializer = keySerializer;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getNativeCache() {
        return cache;
    }

    @Override
    public ValueWrapper get(Object key) {
        SpringCacheEntry entry = cache.get(toCacheKey(key));
        return entry == null ? null : new SimpleValueWrapper(toValue(entry));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Class<T> type) {
        SpringCacheEntry entry = cache.get(toCacheKey(key));
        if (entry == null) {
            return null;
        }
        Object value = toValue(entry);
        if (type == null) {
            return (T) value;
        }
        return type.isInstance(value) ? type.cast(value) : null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        String cacheKey = toCacheKey(key);
        SpringCacheEntry entry = cache.get(cacheKey);
        if (entry != null) {
            return (T) toValue(entry);
        }
        try {
            T loadedValue = valueLoader.call();
            put(key, loadedValue);
            return loadedValue;
        } catch (Exception exception) {
            throw new ValueRetrievalException(key, valueLoader, exception);
        }
    }

    @Override
    public void put(Object key, Object value) {
        if (value == null) {
            return;
        }
        String cacheKey = toCacheKey(key);
        SpringCacheEntry previousEntry = cache.get(cacheKey);
        Object previousValue = previousEntry == null ? null : toValue(previousEntry);
        cache.put(cacheKey, new SpringCacheEntry(value.getClass().getName(), objectMapper.valueToTree(value)));
        publishEvent(new CacheInsertedEvent<>(name, cacheKey, value, previousValue));
    }

    @Override
    public void evict(Object key) {
        String cacheKey = toCacheKey(key);
        SpringCacheEntry removed = cache.evict(cacheKey);
        if (removed != null) {
            publishEvent(new CacheEvictedEvent<>(name, cacheKey, toValue(removed)));
        }
    }

    @Override
    public void clear() {
        List<CacheEvictedEvent<String, Object>> events = new ArrayList<>();
        for (String cacheKey : cache.keys()) {
            SpringCacheEntry entry = cache.get(cacheKey);
            if (entry != null) {
                events.add(new CacheEvictedEvent<>(name, cacheKey, toValue(entry)));
            }
        }
        cache.clear();
        events.forEach(this::publishEvent);
    }

    @Override
    public boolean invalidate() {
        clear();
        return true;
    }

    private String toCacheKey(Object key) {
        return keySerializer.apply(key);
    }

    private Object toValue(SpringCacheEntry entry) {
        try {
            Class<?> valueClass = Class.forName(entry.getType());
            return objectMapper.treeToValue(entry.getValue(), valueClass);
        } catch (ClassNotFoundException | JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize Spring cache entry", exception);
        }
    }

    private void publishEvent(Object event) {
        if (applicationEventPublisher != null) {
            applicationEventPublisher.publishEvent(event);
        }
    }
}
