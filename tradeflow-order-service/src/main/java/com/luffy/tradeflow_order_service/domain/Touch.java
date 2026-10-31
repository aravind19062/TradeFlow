package com.luffy.tradeflow_order_service.domain;

import java.math.BigDecimal;

/**
 * The simulated best bid and ask. Buys execute at the ask, sells at the bid; sell stops trigger on
 * the bid (the price a seller would actually get).
 */
public record Touch(BigDecimal bid, BigDecimal ask) {

    public static Touch flat(BigDecimal price) {
        return new Touch(price, price);
    }
}
