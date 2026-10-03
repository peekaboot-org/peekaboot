package org.peekaboot.testingapp.inventory;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Plain JdbcClient on the inventory DataSource, so its queries carry no Hibernate in their
 * trace; product lookups go through the Redis cache.
 */
@Repository
public class InventoryRepository {

    private final JdbcClient jdbcClient;

    public InventoryRepository(@Qualifier("inventory") JdbcClient jdbcClient) {

        this.jdbcClient = jdbcClient;
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
}
