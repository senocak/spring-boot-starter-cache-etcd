package com.github.senocak.etcd.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.etcd.jetcd.Client;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Spring CacheManager that stores all managed cache entries in etcd.
 *
 * <p>Expired entries are reclaimed by etcd through per-entry leases, so this manager runs no
 * background eviction of its own. It does not own the {@link Client} it is handed and therefore
 * never closes it.
 */
public final class EtcdCacheManager implements CacheManager {
    private final Function<String, EtcdCache<String, SpringCacheEntry>> cacheFactory;
    private final ObjectMapper objectMapper;
    private final Function<Object, String> keySerializer;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ConcurrentHashMap<String, Cache> caches = new ConcurrentHashMap<>();

    public EtcdCacheManager(Function<String, EtcdCache<String, SpringCacheEntry>> cacheFactory,
                            ObjectMapper objectMapper) {
        this(cacheFactory, objectMapper, Object::toString, null);
    }

    public EtcdCacheManager(Function<String, EtcdCache<String, SpringCacheEntry>> cacheFactory,
                            ObjectMapper objectMapper,
                            Function<Object, String> keySerializer,
                            ApplicationEventPublisher applicationEventPublisher) {
        this.cacheFactory = cacheFactory;
        this.objectMapper = objectMapper;
        this.keySerializer = keySerializer;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    public EtcdCacheManager(Client etcdClient) {
        this(etcdClient, EtcdCacheImpl.DEFAULT_KEY_PREFIX);
    }

    public EtcdCacheManager(Client etcdClient, String keyPrefix) {
        this(etcdClient, keyPrefix, EtcdCacheImpl.defaultObjectMapper(), Object::toString, null, null);
    }

    public EtcdCacheManager(Client etcdClient,
                            String keyPrefix,
                            ObjectMapper objectMapper,
                            Function<Object, String> keySerializer,
                            ApplicationEventPublisher applicationEventPublisher,
                            Duration entryTtl) {
        this(
            cacheName -> new EtcdCacheImpl<>(
                cacheName,
                String.class,
                SpringCacheEntry.class,
                etcdClient,
                keyPrefix,
                objectMapper,
                null,
                entryTtl
            ),
            objectMapper,
            keySerializer,
            applicationEventPublisher
        );
    }

    @Override
    public Cache getCache(final String name) {
        return caches.computeIfAbsent(name, cacheName -> new EtcdBackedSpringCache(
            cacheName,
            cacheFactory.apply(cacheName),
            objectMapper,
            keySerializer,
            applicationEventPublisher
        ));
    }

    @Override
    public Collection<String> getCacheNames() {
        return List.copyOf(caches.keySet());
    }

    public void clearAll() {
        caches.values().forEach(Cache::clear);
    }

    public int getCacheCount() {
        return caches.size();
    }
}
