package com.github.senocak.etcd

import io.etcd.jetcd.launcher.Etcd
import io.etcd.jetcd.launcher.EtcdCluster
import java.net.URI
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

abstract class EtcdContainerIntegrationTestSupport {
    companion object {
        @JvmStatic
        val etcdCluster: EtcdCluster = Etcd.builder()
            .withClusterName("spring-cache-etcd-it")
            .withNodes(1)
            .build()

        init {
            etcdCluster.start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun registerEtcdProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.etcd.endpoints") {
                etcdCluster.clientEndpoints().joinToString(separator = ",") { uri: URI -> uri.toString() }
            }
        }
    }
}
