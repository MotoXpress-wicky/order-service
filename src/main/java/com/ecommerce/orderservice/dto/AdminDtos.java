package com.ecommerce.orderservice.dto;

import java.math.BigDecimal;

/** Shapes used only by the admin pages. */
public final class AdminDtos {

    private AdminDtos() {
    }

    /**
     * The numbers on the admin dashboard.
     *
     * @param toShip                 orders waiting to be handed to a courier
     * @param withCourier            parcels on the road
     * @param cashWithCouriersCount  delivered parcels whose cash the courier still holds
     * @param cashWithCouriersAmount the cash itself
     * @param overdueCount           of those, how many are older than overdueDays
     * @param overdueAmount          and their cash
     */
    public record OrderCounts(long toShip, long withCourier, long cashWithCouriersCount,
                              BigDecimal cashWithCouriersAmount, long overdueCount, BigDecimal overdueAmount,
                              int overdueDays, String currency) {
    }
}
