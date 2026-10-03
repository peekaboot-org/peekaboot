package org.peekaboot.testingapp.order;

import org.peekaboot.testingapp.inventory.InventoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Reads the remaining stock after commit on a task executor, so the trace shows a listener span in the async task. */
@Component
public class StockLevelListener {

    private static final Logger log = LoggerFactory.getLogger(StockLevelListener.class);

    private final InventoryRepository inventoryRepository;

    public StockLevelListener(InventoryRepository inventoryRepository) {

        this.inventoryRepository = inventoryRepository;
    }

    @Async
    @TransactionalEventListener
    public void onOrderPlaced(OrderPlacedEvent event) {

        int remaining = inventoryRepository.stockLevel(event.sku());
        log.info("{} has {} left after order {}", event.sku(), remaining, event.reference());
    }
}
