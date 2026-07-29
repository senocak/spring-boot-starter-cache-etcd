package com.github.senocak.etcd.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.KeyValue;
import io.etcd.jetcd.Lease;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.PutOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Stores every cache entry under its own etcd key.
 *
 * <p>Key layout is {@code {keyPrefix}/{cacheName}/{sha256(cacheKey)}}, which keeps writes
 * idempotent for the same key and makes per-cache listing a single prefix scan.
 *
 * <p>Entry expiry is delegated to etcd leases, so expired keys are reclaimed server-side with no
 * cooperation from the application. etcd grants leases in whole seconds, so any sub-second TTL is
 * rounded up to one second.
 */
public final class EtcdCacheDocumentStore implements CacheDocumentStore {
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final KV kvClient;
    private final Lease leaseClient;
    private final String keyPrefix;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;

    public EtcdCacheDocumentStore(Client etcdClient) {
        this(etcdClient, EtcdCacheImpl.DEFAULT_KEY_PREFIX);
    }

    public EtcdCacheDocumentStore(Client etcdClient, String keyPrefix) {
        this(etcdClient, keyPrefix, EtcdCacheImpl.defaultObjectMapper(), DEFAULT_REQUEST_TIMEOUT);
    }

    public EtcdCacheDocumentStore(Client etcdClient, String keyPrefix, ObjectMapper objectMapper,
                                  Duration requestTimeout) {
        if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("Request timeout must be positive");
        }
        this.kvClient = etcdClient.getKVClient();
        this.leaseClient = etcdClient.getLeaseClient();
        this.keyPrefix = sanitizeKeyPrefix(keyPrefix);
        this.objectMapper = objectMapper;
        this.requestTimeout = requestTimeout;
    }

    @Override
    public CacheDocument get(String cacheName, String cacheKey) {
        GetResponse response = await(kvClient.get(documentKey(cacheName, cacheKey)), "read cache document");
        if (response.getKvs().isEmpty()) {
            return null;
        }
        CacheDocument document = readDocument(response.getKvs().getFirst());
        return document.getCacheName().equals(cacheName) && document.getCacheKey().equals(cacheKey)
            ? document
            : null;
    }

    @Override
    public void put(CacheDocument document, Duration ttl) {
        ByteSequence key = documentKey(document.getCacheName(), document.getCacheKey());
        ByteSequence value = utf8(writeDocument(document));
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            await(kvClient.put(key, value), "write cache document");
            return;
        }
        long leaseId = await(leaseClient.grant(ceilSeconds(ttl)), "grant cache entry lease").getID();
        await(kvClient.put(key, value, PutOption.builder().withLeaseId(leaseId).build()),
            "write cache document");
    }

    @Override
    public CacheDocument delete(String cacheName, String cacheKey) {
        CacheDocument existing = get(cacheName, cacheKey);
        if (existing == null) {
            return null;
        }
        await(kvClient.delete(documentKey(cacheName, cacheKey)), "delete cache document");
        return existing;
    }

    @Override
    public List<CacheDocument> findAll(String cacheName) {
        GetResponse response = await(
            kvClient.get(utf8(cachePrefix(cacheName)), GetOption.builder().isPrefix(true).build()),
            "list cache documents"
        );
        return response.getKvs().stream()
            .map(this::readDocument)
            .filter(document -> document.getCacheName().equals(cacheName))
            .toList();
    }

    /** etcd grants leases in whole seconds, so anything below one second becomes one second. */
    private static long ceilSeconds(Duration ttl) {
        return Math.max(1L, (ttl.toMillis() + 999L) / 1000L);
    }

    private ByteSequence documentKey(String cacheName, String cacheKey) {
        return utf8(cachePrefix(cacheName) + sha256Hex(cacheKey));
    }

    private String cachePrefix(String cacheName) {
        return keyPrefix + '/' + cacheName + '/';
    }

    private static ByteSequence utf8(String value) {
        return ByteSequence.from(value, StandardCharsets.UTF_8);
    }

    private String writeDocument(CacheDocument document) {
        try {
            return objectMapper.writeValueAsString(document);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize cache document", exception);
        }
    }

    private CacheDocument readDocument(KeyValue keyValue) {
        try {
            return objectMapper.readValue(keyValue.getValue().toString(StandardCharsets.UTF_8), CacheDocument.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize cache document", exception);
        }
    }

    private <T> T await(CompletableFuture<T> future, String operation) {
        try {
            return future.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw operationFailed(operation, exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw operationFailed(operation, exception);
        }
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable", exception);
        }
    }

    private static String sanitizeKeyPrefix(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalArgumentException("etcd key prefix must not be blank");
        }
        boolean containsInvalidCharacter = candidate.chars()
            .anyMatch(character -> Character.isWhitespace(character) || Character.isISOControl(character));
        if (containsInvalidCharacter) {
            throw new IllegalArgumentException("etcd key prefix must not contain whitespace or control characters");
        }
        String normalized = candidate;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("etcd key prefix must contain more than slashes");
        }
        return '/' + normalized;
    }

    private static IllegalStateException operationFailed(String operation, Exception exception) {
        return new IllegalStateException("Failed to " + operation + " in etcd", exception);
    }
}
