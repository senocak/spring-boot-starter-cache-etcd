package com.github.senocak.etcd

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.cache.annotation.EnableCaching

@SpringBootApplication
@EnableCaching
@ConfigurationPropertiesScan
class EtcdCacheApplication

fun main(args: Array<String>) {
    runApplication<EtcdCacheApplication>(*args)
}

fun <R : Any> R.logger(): Lazy<Logger> = lazy {
    LoggerFactory.getLogger((if (javaClass.kotlin.isCompanion) javaClass.enclosingClass else javaClass).name)
}
