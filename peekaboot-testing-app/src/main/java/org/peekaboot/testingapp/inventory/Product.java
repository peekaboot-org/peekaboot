package org.peekaboot.testingapp.inventory;

import java.io.Serializable;
import java.math.BigDecimal;

/** Serializable because the Redis cache stores it with JDK serialization, Boot's default. */
public record Product(String sku, String name, BigDecimal price) implements Serializable {}
