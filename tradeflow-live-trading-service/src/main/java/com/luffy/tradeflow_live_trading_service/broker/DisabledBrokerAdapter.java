package com.luffy.tradeflow_live_trading_service.broker;

import com.luffy.tradeflow.common.web.ApiException;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * Used while no broker is approved. Every call is refused: nothing can reach a real broker and no
 * real order can be placed. Replacing it requires an approved broker adapter (phase 6).
 */
public class DisabledBrokerAdapter implements BrokerAdapter {

    private static ApiException refused() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BROKER_NOT_CONFIGURED",
                "No broker is integrated yet. Live trading will open once a broker has been approved.");
    }

    @Override public String code() { return "none"; }
    @Override public boolean enabled() { return false; }
    @Override public String authorizationUrl(String state, String codeChallenge) { throw refused(); }
    @Override public BrokerSession exchangeCode(String code, String codeVerifier) { throw refused(); }
    @Override public void revoke(String accessToken) { throw refused(); }
    @Override public Funds funds(String accessToken) { throw refused(); }
    @Override public List<Holding> holdings(String accessToken) { throw refused(); }
    @Override public List<Position> positions(String accessToken) { throw refused(); }
    @Override public List<BrokerOrder> orders(String accessToken) { throw refused(); }
    @Override public BrokerOrderAck placeOrder(String accessToken, OrderRequest request) { throw refused(); }
    @Override public BrokerOrderAck cancelOrder(String accessToken, String brokerOrderId) { throw refused(); }
    @Override public List<Quote> quotes(String accessToken, List<String> instruments) { throw refused(); }
}
