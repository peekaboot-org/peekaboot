package org.peekaboot.testingapp.controller;

import org.peekaboot.testingapp.inventory.InventoryRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
public class InventoryController {

    private final InventoryRepository inventoryRepository;

    public InventoryController(InventoryRepository inventoryRepository) {

        this.inventoryRepository = inventoryRepository;
    }

    /** Puts every SKU back to its seeded quantity, so a demo that drained the stock can go on. */
    @PostMapping("/inventory/restock")
    public String restock() {

        inventoryRepository.restockAll();
        return "redirect:/orders";
    }
}
