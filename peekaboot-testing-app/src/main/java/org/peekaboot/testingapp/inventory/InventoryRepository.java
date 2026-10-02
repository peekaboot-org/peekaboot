package org.peekaboot.testingapp.inventory;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Plain JdbcClient on the inventory DataSource, so its queries carry no Hibernate in their trace. */
@Repository
public class InventoryRepository {

    private final JdbcClient jdbcClient;

    public InventoryRepository(@Qualifier("inventory") JdbcClient jdbcClient) {

        this.jdbcClient = jdbcClient;
    }

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
}
