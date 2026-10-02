package org.peekaboot.testingapp.inventory;

import java.math.BigDecimal;

public record Product(String sku, String name, BigDecimal price) {}
