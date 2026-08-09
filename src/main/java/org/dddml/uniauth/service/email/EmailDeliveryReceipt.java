package org.dddml.uniauth.service.email;

public record EmailDeliveryReceipt(
        String deliveryId,
        DeliveryState state) {

    public enum DeliveryState {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED
    }
}
