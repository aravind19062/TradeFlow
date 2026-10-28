package com.luffy.tradeflow_portfolio_service.assistant;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fixed, reviewed definitions of every metric the portfolio API reports. The assistant
 * explains metrics from this table (tool explain_metric) instead of letting a model
 * improvise a definition.
 */
public final class MetricDefinitions {

    public static final Map<String, String> DEFINITIONS;

    static {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("average_cost", "Weighted average cost per share: each buy blends into the average "
                + "((old quantity × old average + cost of the buy) / new quantity, 4 decimals); sells do not change it.");
        d.put("realized_pnl", "Profit or loss booked by completed sells: (sell price − average cost) × quantity sold. "
                + "No fees or taxes are modelled.");
        d.put("unrealized_pnl", "Paper profit or loss on shares still held: (latest simulated price − average cost) × "
                + "quantity. It changes with every simulated tick and is not locked in.");
        d.put("total_return", "Total P&L divided by net deposits: (total value − initial simulated deposit) / deposit. "
                + "Simple return; the only external cash flow is the initial deposit.");
        d.put("allocation", "Each position's market value divided by total portfolio value (cash included), also "
                + "grouped by sector and asset class.");
        d.put("concentration", "Herfindahl index (HHI) over invested holdings: the sum of squared position weights. "
                + "1.0 = one position; N equal positions = 1/N. Effective number of holdings = 1/HHI. A position above "
                + "25% or a sector above 40% of total value is flagged; these thresholds are TradeFlow conventions.");
        d.put("max_drawdown", "Largest fall from a peak to a later trough of total portfolio value, as a fraction of "
                + "the peak, over the history available since the account opened. Values are rebuilt from the ledger "
                + "and simulated prices, so they describe the demo portfolio, not real markets.");
        d.put("reserved_cash", "Cash earmarked for open BUY orders (quantity × reservation price). It is still part of "
                + "the cash balance but cannot be used for new orders until the order fills or is cancelled.");
        DEFINITIONS = Map.copyOf(d);
    }

    private MetricDefinitions() {
    }
}
