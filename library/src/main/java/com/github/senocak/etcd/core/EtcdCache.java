package com.github.senocak.etcd.core;

import java.util.Set;

/** Minimal typed cache contract backed by an implementation-defined persistence layer. */
public interface EtcdCache<K, V> {
    V get(K key);
    void put(K key, V value);
    V evict(K key);
    void clear();
    boolean containsKey(K key);
    int size();
    Set<K> keys();
}
