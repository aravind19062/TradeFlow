package com.luffy.tradeflow_market_data_service.web;

import com.luffy.tradeflow.common.money.Money;
import com.luffy.tradeflow_market_data_service.domain.Instrument;
import com.luffy.tradeflow_market_data_service.domain.PriceAlert;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MarketDtos {

    private MarketDtos() {
    }

    public record InstrumentResponse(String symbol, String name, String sector, String assetClass, String currency) {
        public static InstrumentResponse from(Instrument i) {
            return new InstrumentResponse(i.getSymbol(), i.getName(), i.getSector(), i.getAssetClass(),
                    i.getCurrency());
        }
    }

    /** {@code source} is always SIMULATED: these are not live market prices. */
    public record QuoteResponse(String symbol, String name, String sector, String assetClass, String currency,
                                BigDecimal price, BigDecimal previousPrice, BigDecimal change, BigDecimal changeRatio,
                                long tick, Instant asOf, String source) {
    }

    public record PricePoint(long tick, BigDecimal price, Instant asOf) {
    }

    public record HistoryResponse(String symbol, String source, List<PricePoint> points) {
    }

    public record SimulationResponse(long currentTick, String source, String note) {
    }

    public record AlertRequest(
            @NotBlank String symbol,
            @NotNull PriceAlert.Direction direction,
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 15, fraction = 2) BigDecimal threshold,
            @Size(max = 200) String note) {
    }

    public record AlertResponse(UUID id, String symbol, PriceAlert.Direction direction, BigDecimal threshold,
                                PriceAlert.Status status, String note, Instant createdAt, Instant triggeredAt,
                                BigDecimal triggeredPrice) {
        public static AlertResponse from(PriceAlert a) {
            return new AlertResponse(a.getId(), a.getSymbol(), a.getDirection(), Money.price(a.getThreshold()),
                    a.getStatus(), a.getNote(), a.getCreatedAt(), a.getTriggeredAt(),
                    a.getTriggeredPrice() == null ? null : Money.price(a.getTriggeredPrice()));
        }
    }
}
