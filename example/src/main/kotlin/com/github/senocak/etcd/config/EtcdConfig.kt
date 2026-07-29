package com.github.senocak.etcd.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.senocak.etcd.core.EtcdCacheImpl
import com.github.senocak.etcd.core.EtcdCacheManager
import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.ClientBuilder
import java.nio.charset.StandardCharsets
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cache.CacheManager
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(value = [
    EtcdProperties::class,
    EtcdCacheProperties::class,
])
class EtcdConfig {
    @Bean(destroyMethod = "close")
    fun etcdClient(etcdProperties: EtcdProperties): Client {
        val endpoints: List<String> = etcdProperties.endpoints
            .ifEmpty { listOf(element = "http://localhost:2379") }
        val clientBuilder: ClientBuilder = Client.builder().endpoints(*endpoints.toTypedArray())
        if (!etcdProperties.user.isNullOrBlank() && !etcdProperties.password.isNullOrBlank()) {
            clientBuilder
                .user(etcdProperties.user.toByteSequence())
                .password(etcdProperties.password.toByteSequence())
        }
        if (!etcdProperties.namespace.isNullOrBlank()) {
            clientBuilder.namespace(etcdProperties.namespace.toByteSequence())
        }
        return clientBuilder.build()
    }

    @Bean(value = ["etcdCacheManager"])
    fun etcdCacheManager(
        etcdClient: Client,
        etcdCacheProperties: EtcdCacheProperties,
        objectMapper: ObjectMapper,
        applicationEventPublisher: ApplicationEventPublisher? = null
    ): CacheManager =
        EtcdCacheManager(
            etcdClient,
            etcdCacheProperties.keyPrefix,
            objectMapper,
            { key: Any -> key.toString() },
            applicationEventPublisher,
            etcdCacheProperties.entryTtl
        )

    private fun String?.toByteSequence(): ByteSequence =
        ByteSequence.from(this ?: error("Value must not be null"), StandardCharsets.UTF_8)
}

@ConfigurationProperties(prefix = "spring.etcd")
data class EtcdProperties(
    var endpoints: List<String> = listOf(element = "http://localhost:2379"),
    var user: String? = null,
    var password: String? = null,
    var namespace: String? = null
)

/**
 * Cache tuning. There is no clear-interval: expiry is enforced by etcd leases, so nothing needs
 * to be swept. Note that etcd grants leases in whole seconds, so a sub-second [entryTtl] is
 * rounded up to one second.
 */
@ConfigurationProperties(prefix = "spring.etcd.cache")
data class EtcdCacheProperties(
    var keyPrefix: String = EtcdCacheImpl.DEFAULT_KEY_PREFIX,
    var entryTtl: Duration? = null
)
