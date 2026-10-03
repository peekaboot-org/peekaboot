package org.peekaboot.autoconfigure.peekabootfixture;

import com.example.listener.Listeners;
import org.springframework.context.event.EventListener;

/** Stands for a listener in the auto-configuration module's package, which the instrumentation leaves alone. */
public class AutoConfigurationListener {

    @EventListener
    public void onOrderPlaced(Listeners.OrderPlaced event) {}
}
