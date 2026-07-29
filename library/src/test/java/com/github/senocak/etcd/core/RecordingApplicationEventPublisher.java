package com.github.senocak.etcd.core;

import java.util.ArrayList;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;

final class RecordingApplicationEventPublisher implements ApplicationEventPublisher {
    final List<Object> events = new ArrayList<>();

    @Override
    public void publishEvent(Object event) {
        events.add(event);
    }
}
