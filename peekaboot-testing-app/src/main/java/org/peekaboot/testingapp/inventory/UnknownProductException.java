package org.peekaboot.testingapp.inventory;

import java.io.Serial;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class UnknownProductException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public UnknownProductException(String sku) {

        super("no product " + sku);
    }
}
