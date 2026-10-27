package com.luffy.tradeflow_portfolio_service.web;

import com.luffy.tradeflow.common.money.Money;
import com.luffy.tradeflow_portfolio_service.domain.LedgerEntry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read models returned by the portfolio API (and used as facts by the AI assistant). */
public final class PortfolioViews {

    private PortfolioViews() {
    }

    public record HoldingView(String symbol, String name, String sector, String assetClass, long quantity,
                              long reservedQuantity, BigDecimal averageCost, BigDecimal costBasis,
                              BigDecimal marketPrice, BigDecimal marketValue, BigDecimal unrealizedPnl,
                              BigDecimal unrealizedPnlRatio, BigDecimal realizedPnl, BigDecimal weight) {
    }

    /**
     * @param totalPnl         totalValue − netDeposits (realized + unrealized)
     * @param pricesAvailable  false when market data could not be reached; holdings are then valued at cost
     */
    public record PortfolioSummary(long userId, String currency, BigDecimal cashBalance, BigDecimal reservedCash,
                                   BigDecimal availableCash, BigDecimal holdingsMarketValue,
                                   BigDecimal holdingsCostBasis, BigDecimal totalValue, BigDecimal netDeposits,
                                   BigDecimal realizedPnl, BigDecimal unrealizedPnl, BigDecimal totalPnl,
                                   BigDecimal totalReturnRatio, List<HoldingView> holdings, boolean pricesAvailable,
                                   Instant pricesAsOf, String priceSource, List<String> notes) {
    }

    public record AllocationSlice(String name, BigDecimal value, BigDecimal weight) {
    }

    public record RiskReport(BigDecimal totalValue, BigDecimal cashWeight, BigDecimal investedWeight,
                             String largestPosition, BigDecimal largestPositionWeight, BigDecimal herfindahlIndex,
                             BigDecimal effectiveNumberOfHoldings, List<AllocationSlice> bySymbol,
                             List<AllocationSlice> bySector, List<AllocationSlice> byAssetClass,
                             BigDecimal maxDrawdownRatio, List<String> warnings, List<String> methodology,
                             boolean pricesAvailable) {
    }

    public record PerformancePoint(Instant at, BigDecimal cash, BigDecimal holdingsValue, BigDecimal totalValue) {
    }

    public record PerformanceReport(Instant from, Instant to, BigDecimal startValue, BigDecimal endValue,
                                    BigDecimal change, BigDecimal changeRatio, BigDecimal peakValue,
                                    BigDecimal maxDrawdownRatio, int pointCount, List<PerformancePoint> points,
                                    String priceSource, List<String> notes) {
    }

    public record LedgerEntryView(long id, LedgerEntry.Type type, String symbol, Long quantity, BigDecimal price,
                                  BigDecimal cashDelta, BigDecimal cashBalanceAfter, BigDecimal realizedPnl,
                                  UUID tradeId, UUID orderId, Instant occurredAt) {
        public static LedgerEntryView from(LedgerEntry e) {
            return new LedgerEntryView(e.getId(), e.getType(), e.getSymbol(), e.getQuantity(),
                    e.getPrice() == null ? null : Money.price(e.getPrice()), Money.cash(e.getCashDelta()),
                    Money.cash(e.getCashBalanceAfter()),
                    e.getRealizedPnl() == null ? null : Money.cash(e.getRealizedPnl()), e.getTradeId(),
                    e.getOrderId(), e.getOccurredAt());
        }
    }

    public record ReconciliationIssue(long userId, String check, String expected, String actual) {
    }

    public record ReconciliationReport(Instant checkedAt, int accountsChecked, boolean consistent,
                                       List<ReconciliationIssue> issues) {
    }
}
