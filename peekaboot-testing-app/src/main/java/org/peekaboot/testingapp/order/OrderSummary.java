package org.peekaboot.testingapp.order;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderSummary(
        Long id,
        String reference,
        String status,
        Instant placedAt,
        int lineCount,
        BigDecimal total,
        String customerName) {
}
