package com.example.listener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.EventListener;

/** Stands for an application's catch-all listener: it hears every event its context publishes. */
public class EverythingListener {

    private final List<Object> heard = new CopyOnWriteArrayList<>();

    @EventListener
    public void on(Object event) {
        heard.add(event instanceof PayloadApplicationEvent<?> wrapped ? wrapped.getPayload() : event);
    }

    public List<Object> heard() {
        return heard;
    }
}
