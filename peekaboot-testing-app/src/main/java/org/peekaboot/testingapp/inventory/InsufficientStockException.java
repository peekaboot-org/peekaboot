package org.peekaboot.testingapp.inventory;

import java.io.Serial;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class InsufficientStockException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InsufficientStockException(String sku, int quantity) {

        super("not enough stock of " + sku + " for " + quantity);
    }
}
