package org.springframework.peekabootfixture;

import com.example.listener.Listeners;
import org.springframework.context.event.EventListener;

/** Stands for a listener in Spring's own namespace, which the instrumentation leaves alone. */
public class FrameworkListener {

    @EventListener
    public void onOrderPlaced(Listeners.OrderPlaced event) {}
}
