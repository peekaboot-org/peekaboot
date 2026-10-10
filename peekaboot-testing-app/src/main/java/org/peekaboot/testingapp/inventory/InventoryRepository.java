package org.peekaboot.testingapp.inventory;

import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Plain JdbcClient on the inventory DataSource, so its queries carry no Hibernate in their
 * trace; product lookups go through the Redis cache.
 */
@Repository
public class InventoryRepository {

    /** The seed-products change set's quantities, as the restock's parameters: quantity, then SKU. */
    private static final List<Object[]> SEEDED_STOCK = List.of(
            new Object[] {500, "WIDGET-1"},
            new Object[] {500, "WIDGET-2"},
            new Object[] {500, "WIDGET-3"},
            new Object[] {500, "WIDGET-4"},
            new Object[] {50, "GADGET-1"},
            new Object[] {50, "GADGET-2"},
            new Object[] {50, "GADGET-3"});

    private final JdbcClient jdbcClient;

    private final JdbcTemplate jdbcTemplate;

    public InventoryRepository(
            @Qualifier("inventory") JdbcClient jdbcClient, @Qualifier("inventory") JdbcTemplate jdbcTemplate) {

        this.jdbcClient = jdbcClient;
        this.jdbcTemplate = jdbcTemplate;
    }

    // #result is the Optional's content; caching a miss would hide a product added within the TTL.
    @Cacheable(cacheNames = "products", unless = "#result == null")
    public Optional<Product> findProduct(String sku) {

        return jdbcClient
                .sql("SELECT sku, name, price FROM product WHERE sku = ?")
                .param(sku)
                .query(Product.class)
                .optional();
    }

    /** One conditional UPDATE, so two concurrent orders can never take the same last item. */
    public void reserveStock(String sku, int quantity) {

        int reserved = jdbcClient
                .sql("UPDATE stock SET quantity = quantity - ? WHERE sku = ? AND quantity >= ?")
                .param(quantity)
                .param(sku)
                .param(quantity)
                .update();
        if (reserved == 0) {
            throw new InsufficientStockException(sku, quantity);
        }
    }

    /** Uncached on purpose, so every order's after-commit check shows its inventory query. */
    public int stockLevel(String sku) {

        return jdbcClient
                .sql("SELECT quantity FROM stock WHERE sku = ?")
                .param(sku)
                .query(Integer.class)
                .single();
    }

    /** One batched UPDATE for every SKU, so the trace holds one statement with seven parameter sets. */
    public void restockAll() {

        jdbcTemplate.batchUpdate("UPDATE stock SET quantity = ? WHERE sku = ?", SEEDED_STOCK);
    }
}
