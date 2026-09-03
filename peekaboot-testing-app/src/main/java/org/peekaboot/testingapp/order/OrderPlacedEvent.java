package org.peekaboot.testingapp.order;

public record OrderPlacedEvent(Long orderId, String reference) {
}
