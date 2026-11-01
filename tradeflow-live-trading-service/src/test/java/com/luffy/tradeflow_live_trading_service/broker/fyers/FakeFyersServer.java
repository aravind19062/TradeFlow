package com.luffy.tradeflow_live_trading_service.broker.fyers;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A local stand-in for the Fyers API v3 host. Responses follow the shapes of the samples in Fyers'
 * API documentation (field names and codes); every request is recorded so tests can check exactly
 * what TradeFlow sent, and that nothing was sent at all where it must not be.
 */
public class FakeFyersServer implements AutoCloseable {

    public record Request(String method, String path, String query, String authorization, String body) {
    }

    public record Response(int status, String body) {
    }

    public static final String TOKEN = "fyers-access-token-SECRET-0001";

    public final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Response> responses = new ConcurrentHashMap<>();
    private final HttpServer server;

    public FakeFyersServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        defaults();
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getPath();
            requests.add(new Request(exchange.getRequestMethod(), path, exchange.getRequestURI().getRawQuery(),
                    exchange.getRequestHeaders().getFirst("Authorization"), body));
            Response r = responses.getOrDefault(path, new Response(404, "{\"s\":\"error\",\"code\":-1,\"message\":\"not found\"}"));
            byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(r.status(), out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    public String apiUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3";
    }

    public String dataUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/data";
    }

    public void respond(String path, int status, String body) {
        responses.put(path, new Response(status, body));
    }

    public List<Request> requestsTo(String path) {
        return requests.stream().filter(r -> r.path().equals(path)).toList();
    }

    private void defaults() {
        respond("/api/v3/validate-authcode", 200, """
                {"s":"ok","code":200,"message":"","access_token":"%s","refresh_token":"fyers-refresh-token-SECRET"}"""
                .formatted(TOKEN));
        respond("/api/v3/profile", 200, """
                {"s":"ok","code":200,"message":"","data":{"fy_id":"XA00001","name":"TEST USER","display_name":null,
                 "email_id":"t***@example.com","PAN":"AB***1234F","mobile_number":"98****0000","totp":true}}""");
        respond("/api/v3/funds", 200, """
                {"code":200,"message":"","s":"ok","fund_limit":[
                 {"id":1,"title":"Total Balance","equityAmount":61686.08,"commodityAmount":0},
                 {"id":2,"title":"Utilized Amount","equityAmount":1350.5,"commodityAmount":0},
                 {"id":3,"title":"Clear Balance","equityAmount":61686.08,"commodityAmount":0},
                 {"id":10,"title":"Available Balance","equityAmount":60335.58,"commodityAmount":0}]}""");
        respond("/api/v3/holdings", 200, """
                {"code":200,"message":"","s":"ok","overall":{"count_total":1,"total_pl":4.26},
                 "holdings":[{"symbol":"NSE:IDEA-EQ","quantity":2,"remainingQuantity":2,"costPrice":9.72,"ltp":11.85,
                 "pl":4.26,"marketVal":23.7,"exchange":10,"segment":10,"holdingType":"HLD","fyToken":"101000000014366","id":1}]}""");
        respond("/api/v3/positions", 200, """
                {"s":"ok","code":200,"message":"","netPositions":[{"symbol":"NSE:SBIN-EQ","id":"NSE:SBIN-EQ-INTRADAY",
                 "netQty":-3,"qty":3,"avgPrice":590.5,"netAvg":590.5,"side":-1,"productType":"INTRADAY","pl":12.3,
                 "realized_profit":0,"unrealized_profit":12.3,"ltp":586.4}],"overall":{"count_total":1}}""");
        respond("/api/v3/orders", 200, """
                {"s":"ok","code":200,"message":"","orderBook":[
                 {"id":"23080444447604","exchange":10,"symbol":"NSE:IDEA-EQ","qty":10,"filledQty":4,"remainingQuantity":6,
                  "limitPrice":8.1,"stopPrice":0,"tradedPrice":8.05,"type":1,"side":1,"status":6,
                  "orderDateTime":"02-Aug-2023 13:01:42","message":"","orderTag":"1:L-TEST-0001","productType":"CNC"},
                 {"id":"23080444447605","exchange":10,"symbol":"NSE:TCS-EQ","qty":1,"filledQty":0,"remainingQuantity":1,
                  "limitPrice":0,"stopPrice":0,"tradedPrice":0,"type":2,"side":-1,"status":5,
                  "orderDateTime":"02-Aug-2023 13:05:00","message":"RMS: insufficient funds","orderTag":"2:Fyers","productType":"CNC"},
                 {"id":"23080444447606","exchange":10,"symbol":"NSE:INFY-EQ","qty":1,"filledQty":0,"remainingQuantity":1,
                  "limitPrice":0,"stopPrice":0,"tradedPrice":0,"type":2,"side":1,"status":3,
                  "orderDateTime":"02-Aug-2023 13:06:00","message":"","orderTag":"","productType":"CNC"}]}""");
        respond("/data/quotes", 200, """
                {"code":200,"message":"","s":"ok","d":[
                 {"n":"NSE:SBIN-EQ","s":"ok","v":{"lp":590.5,"bid":590.45,"ask":590.6,"tt":"1691020800","short_name":"SBIN-EQ",
                  "exchange":"NSE","symbol":"NSE:SBIN-EQ"}},
                 {"n":"NSE:NOPE-EQ","s":"error","v":{"errmsg":"invalid symbol"}}]}""");
        respond("/api/v3/logout", 200, """
                {"s":"ok","code":200,"message":"you are successfully logged out"}""");
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
