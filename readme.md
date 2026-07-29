# Spring Boot Starter Cache Etcd
Default Spring caching setups are often in-memory and process-local. That creates real operational issues in distributed systems:
- cache state is lost on app restart/deploy
- each pod/node has different cache contents
- horizontal scaling reduces cache hit ratio
- stale data is harder to evict globally
- cached data is not inspectable centrally

This project solves that by persisting cache entries in etcd, so cache state can survive restarts and be shared across multiple app instances. It keeps Spring Cache ergonomics (`@Cacheable`, `@CachePut`, `@CacheEvict`) while moving cache state out of process memory and into a shared, strongly consistent key-value store.

Use this when:

- you already operate etcd reliably (for example, you run Kubernetes)
- you need persistent/shared cache state
- you want to keep Spring Cache annotations
- you want TTL enforced by the datastore rather than by your application

## Architecture and data model
### Request flow
`Spring Cache API` → `EtcdCacheManager` → `EtcdBackedSpringCache` → `EtcdCacheImpl` → `EtcdCacheDocumentStore` → `etcd`

### Key layout
Each cache entry is one etcd key:

```
{keyPrefix}/{cacheName}/{sha256(cacheKey)}   ->   JSON(CacheDocument)
```

The default prefix is `/spring_cache_entries` (`EtcdCacheImpl.DEFAULT_KEY_PREFIX`). Hashing the cache key keeps arbitrary key payloads safe to embed in an etcd key path, and makes writes naturally idempotent for the same key. Grouping by cache name means listing one cache is a single prefix scan.

### Stored document shape
- `cacheName`: logical cache bucket
- `cacheKey`: serialized key used for lookup
- `keyJson`: serialized original key payload
- `valueJson`: serialized value payload

The etcd key holds only a *hash* of the cache key, so the document still carries the real key so that `keys()` can reconstruct it. There is no `expiresAt` field — see below.

## Expiration model
### TTL behavior
TTL is delegated entirely to **etcd leases**. On every write the store grants a lease and attaches it to the key, so etcd reclaims the entry server-side with no cooperation from the application.

- `entryTtl == null` or `0` writes the key without a lease, so it is never reclaimed
- a positive `entryTtl` grants a lease of that length
- **etcd grants leases in whole seconds**, so any sub-second TTL is rounded up to one second
- lease revocation is asynchronous, so a key can briefly outlive its nominal TTL

### What this means in practice
- There is **no background scheduler** and no `clear-interval`. Nothing needs to sweep expired entries.
- Expiry works even if every application instance is down.
- **No `CacheEvictedEvent` is published when an entry expires.** By the time the TTL elapses the key is already gone from etcd, so there is no value left to put in the event. Events are still published for explicit `evict` and `clear`.

If you need an event on expiry, watch the key prefix with etcd's native watch API instead of relying on the cache layer.

## Consistency and concurrency characteristics
- etcd is strongly consistent (Raft), so a successful write is immediately visible to every client.
- Each cache instance uses an internal lock around read-modify-write paths.
- Multi-instance behavior is last-write-wins per key (deterministic key overwrite).
- Eviction events are emitted by the caller instance performing the eviction.

## Performance considerations
- This is a remote persistent cache, not an in-memory O(1) local map.
- `size`, `keys`, and `clear` are prefix scans over the cache's key range.
- `clear` deletes one key at a time so it can emit a `CacheEvictedEvent` per entry. A single ranged prefix delete would be cheaper if you do not need those events.
- Every write with a TTL costs an extra round trip for the lease grant.
- etcd is designed for coordination metadata, not bulk storage. Keep values small, watch the default 1.5 MiB request limit, and do not treat it as a general-purpose blob cache.
- Good fit for shared-state correctness and operational simplicity; not ideal for ultra-low-latency hot paths.

## Comparison with Redis and in-memory cache
### Quick comparison
| Capability / Trade-off | In-memory cache (e.g. local map, Caffeine) | Redis cache | This project (etcd-backed) |
| --- | --- | --- | --- |
| Data survives application restart | No | Usually yes (depends on Redis persistence config) | Yes |
| Shared cache across app instances | No | Yes | Yes |
| Typical read/write latency | Lowest | Low | Higher than Redis/in-memory |
| Consistency model | N/A (local) | Asynchronous replication | Strongly consistent (Raft) |
| TTL enforcement | In-process | Server-side | Server-side (leases, whole seconds) |
| Suitable data volume | Bounded by heap | Large | Small — metadata scale |
| Works with existing etcd ops stack | N/A | No | Yes |
| Best fit | Single-node or ultra-hot local caching | High-throughput distributed low-latency cache | Persistent shared cache where consistency matters |

### How to choose
- Choose **in-memory** when single-instance locality and lowest latency matter most.
- Choose **Redis** when you need distributed caching with high throughput, very low latency, or large data volumes.
- Choose **this etcd-backed cache** when you need Spring-cache-friendly persistence with strong consistency, and you are caching a modest amount of data on infrastructure you already run.

### Important note
This project is not intended to replace Redis for extreme hot-path caching or for large datasets. etcd is a coordination store; it targets workloads where consistency and operability matter more than throughput and capacity.

### Recommended architecture patterns
#### 1) Two-level cache (L1 + L2)
- **L1 (local memory)**: use a very short-lived in-memory cache (for example Caffeine) for hottest reads.
- **L2 (this project)**: use the etcd-backed cache as shared persistent cache across instances.
- **Typical flow**: request → L1 miss → L2 lookup → source of truth (DB/API) → populate L2 and L1.
- **When to use**: high read traffic where you need lower median latency without losing shared persisted cache behavior.

#### 2) Write-through service methods
- Use `@CachePut` on write/update operations so cache state is updated in the same service flow as the source-of-truth write.
- Use `@CacheEvict` on delete/invalidate operations to keep cache and source aligned.
- Best for straightforward CRUD services with predictable cache keys.

#### 3) Lease-driven freshness
- Set `entry-ttl` to the business freshness requirement and let etcd handle the rest — no scheduler to configure or tune.
- Remember the whole-second granularity: a TTL below one second is not expressible.
- Best when stale reads are acceptable only inside a bounded TTL window.

#### 4) Event-observed caching
- Subscribe to `CacheInsertedEvent` and `CacheEvictedEvent` for observability and audit-style logging.
- Use emitted events to track churn and identify noisy keys — but remember expiry is silent, so these events cover explicit writes and evictions only.

## Direct usage
```java
EtcdCacheImpl<String, User> cache = new EtcdCacheImpl<>(
    "users",
    String.class,
    User.class,
    etcdClient,
    "/spring_cache_entries",
    EtcdCacheImpl.defaultObjectMapper(),
    applicationEventPublisher,
    Duration.ofMinutes(10)
);

cache.put("user-1", user);
User cached = cache.get("user-1");
```

## Spring Cache usage
```java
@Configuration
@EnableCaching
class CacheConfig {
    @Bean(destroyMethod = "close")
    Client etcdClient() {
        return Client.builder().endpoints("http://localhost:2379").build();
    }

    @Bean("etcdCacheManager")
    CacheManager cacheManager(
        Client etcdClient,
        ObjectMapper objectMapper,
        ApplicationEventPublisher publisher
    ) {
        return new EtcdCacheManager(
            etcdClient,
            "/spring_cache_entries",
            objectMapper,
            Object::toString,
            publisher,
            Duration.ofMinutes(10)
        );
    }
}
```

Use standard Spring annotations:
```java
@Cacheable(value = "usersByUsername", key = "#username", cacheManager = "etcdCacheManager")
public User getUserByUsername(String username) { /* ... */ }

@CachePut(value = "users", key = "#result.id", cacheManager = "etcdCacheManager")
public User createUser(CreateUserRequest request) { /* ... */ }

@CacheEvict(value = "users", key = "#userId", cacheManager = "etcdCacheManager")
public boolean deleteUser(String userId) { /* ... */ }
```

> **Pass your own `ObjectMapper`.** `EtcdCacheImpl.defaultObjectMapper()` is a plain Jackson mapper with `JavaTimeModule` only. Cached types that need extra modules — Kotlin data classes in particular — fail to deserialize unless you hand in the application's configured mapper, as the example above does.

## Configuration
`example/src/main/resources/application.yml`:
```yaml
spring:
  etcd:
    endpoints: http://localhost:2379
    cache:
      key-prefix: /spring_cache_entries
      entry-ttl: 30s
```

### Config notes
- `endpoints`: comma-separated etcd client URLs
- `key-prefix`: etcd key namespace holding all cache entries
- `entry-ttl`: per-entry lease lifetime; omit or set `0` for entries that never expire

Optional `spring.etcd.user`, `spring.etcd.password`, and `spring.etcd.namespace` are also bound by the example's `EtcdProperties`.

There is deliberately no `clear-interval` — etcd reclaims expired entries itself.

### Netty version requirement
jetcd's Vert.x gRPC transport requires **Netty 4.2+** (`io.netty.channel.MultiThreadIoEventLoopGroup`). Spring Boot 3.5's dependency management still pins Netty 4.1.x, which downgrades the transitive dependency and makes etcd client creation fail at runtime with `NoClassDefFoundError`. Override the managed version in your build:

```xml
<properties>
    <netty.version>4.2.7.Final</netty.version>
</properties>
```

This is safe for servlet-stack applications (Tomcat/Jetty/Undertow), where Netty is used only by the etcd client. If you run WebFlux on Reactor Netty, verify the upgrade against your Spring Boot version first.

## Events
The cache can publish:
- `CacheInsertedEvent`
- `CacheEvictedEvent`

These events are published after successful operations by the cache layer. Expiry is handled by etcd and does **not** produce an event.

## Build and test
Run library tests (no Docker needed — they run against an in-memory store double):
```bash
mvn -f library/pom.xml test
```

Install the library so the example module can resolve it:
```bash
mvn -f library/pom.xml install
```

Run example tests (requires Docker):
```bash
mvn -f example/pom.xml test
```

The example integration tests start a real single-node etcd through `io.etcd:jetcd-launcher`, the official jetcd Testcontainers helper. The container is shared across test classes within one Maven/JVM run, and each test class uses a unique `key-prefix` so the suites cannot collide.

`EtcdLeaseTtlIntegrationTest` is the only place lease expiry is verified. Because etcd leases are whole seconds, it uses a 2s TTL and polls for reclamation rather than sleeping a fixed span.

Build example app:
```bash
mvn -f example/pom.xml -Dmaven.test.skip=true package
```

## Running locally
```bash
docker compose up -d
mvn -f example/pom.xml spring-boot:run
```

This starts etcd on `localhost:2379` plus an [etcdv3-browser](http://localhost:8081) UI, so you can watch cache keys appear under `/spring_cache_entries/` and disappear as their leases expire. Drive the app with `example/src/main/resources/etcd.http`.

## Artifact
```xml
<dependency>
    <groupId>com.github.senocak</groupId>
    <artifactId>spring-boot-starter-cache-etcd</artifactId>
    <version>0.0.4</version>
</dependency>
```

## Request flow diagram

```mermaid
flowchart LR
    A[Application Code\n@Cacheable/@CachePut/@CacheEvict]
    B[EtcdCacheManager]
    C[EtcdBackedSpringCache]
    D[EtcdCacheImpl]
    E[EtcdCacheDocumentStore]
    F[(etcd)]

    A --> B --> C --> D --> E --> F

    classDef request fill:#E3F2FD,stroke:#1565C0,color:#0D47A1;
    classDef cache fill:#E8F5E9,stroke:#2E7D32,color:#1B5E20;
    classDef source fill:#FFF3E0,stroke:#EF6C00,color:#E65100;

    class A request;
    class B,C,D,E cache;
    class F source;

    linkStyle 0,1,2,3,4 stroke:#1565C0,stroke-width:2px,color:#1565C0;
```

## Two-level cache diagram (L1 + L2)

```mermaid
flowchart LR
    R[Incoming Request]
    L1{L1 In-Memory Cache\n(Caffeine/local)}
    L2{L2 Etcd Cache}
    S[(Source of Truth\nDB/API)]
    V[Return Value]

    R --> L1
    L1 -- Hit --> V
    L1 -- Miss --> L2
    L2 -- Hit --> V
    L2 -- Miss --> S
    S --> L2
    S --> L1
    S --> V

    classDef request fill:#E3F2FD,stroke:#1565C0,color:#0D47A1;
    classDef cache fill:#E8F5E9,stroke:#2E7D32,color:#1B5E20;
    classDef source fill:#FFF3E0,stroke:#EF6C00,color:#E65100;
    class R,V request;
    class L1,L2 cache;
    class S source;

    linkStyle 0,5,6,7 stroke:#1565C0,stroke-width:2px,color:#1565C0;
    linkStyle 1,3 stroke:#2E7D32,stroke-width:2px,color:#2E7D32;
    linkStyle 2,4 stroke:#EF6C00,stroke-width:2px,color:#EF6C00;
```

### Diagram legend

- Green `-- Hit -->`: cache key was found at that layer.
- Orange `-- Miss -->`: cache key was not found and lookup continues downstream.
- Blue `-->` (unlabeled): normal request/data flow or cache population path.
