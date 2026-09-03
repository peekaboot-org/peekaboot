package org.peekaboot.testingapp.order;

import java.math.BigDecimal;

public record OrderReport(String reference, int lineCount, BigDecimal total, long computeMillis) {
}
