package org.peekaboot.testingapp.order;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record NewOrder(
        @NotNull Long customerId,
        @NotBlank String sku,
        @Min(1) int quantity) {
}
