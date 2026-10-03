package com.example.listener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.event.EventListener;

/** Stands for an application's own listener: it lives outside every package the instrumentation skips. */
public class RecordingListener {

    public record Ping() {}

    private final List<Ping> heard = new CopyOnWriteArrayList<>();

    @EventListener
    public void onPing(Ping event) {
        heard.add(event);
    }

    public List<Ping> heard() {
        return heard;
    }
}
