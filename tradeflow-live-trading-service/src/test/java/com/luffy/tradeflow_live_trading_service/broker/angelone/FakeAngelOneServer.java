package com.luffy.tradeflow_live_trading_service.broker.angelone;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A local stand-in for the SmartAPI host. Paths and headers follow Angel One's official SDKs;
 * response fields follow the official Java SDK's Order model, Angel One's getHolding announcement
 * and getRMS / getPosition payloads published on Angel One's SmartAPI forum. Every request is
 * recorded so tests can check exactly what TradeFlow sent, and that nothing was sent where it must not be.
 */
public class FakeAngelOneServer implements AutoCloseable {

    public record Request(String method, String path, Map<String, String> headers, String body) {
        public String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    public record Response(int status, String body, String contentType) {
    }

    /** A JWT-shaped auth_token (unsigned test value) that expires in 8 hours. */
    public static final String TOKEN = jwt(Instant.now().plusSeconds(8 * 3600).getEpochSecond());

    public final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Response> responses = new ConcurrentHashMap<>();
    private final HttpServer server;
    private volatile long delayMillis;

    public FakeAngelOneServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        defaults();
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> headers = new ConcurrentHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
            String path = exchange.getRequestURI().getPath();
            requests.add(new Request(exchange.getRequestMethod(), path, headers, body));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Response r = responses.getOrDefault(path, new Response(404,
                    "{\"status\":false,\"message\":\"not found\",\"errorcode\":\"AB0000\",\"data\":null}", "application/json"));
            byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", r.contentType());
            exchange.sendResponseHeaders(r.status(), out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    public static String jwt(long exp) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return b64.encodeToString("{\"alg\":\"HS512\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + b64.encodeToString(("{\"username\":\"A123456\",\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8))
                + ".c2lnbmF0dXJl";
    }

    public String apiUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void respond(String path, int status, String body) {
        responses.put(path, new Response(status, body, "application/json"));
    }

    public void respondRaw(String path, int status, String body, String contentType) {
        responses.put(path, new Response(status, body, contentType));
    }

    public void delay(long millis) {
        this.delayMillis = millis;
    }

    public List<Request> requestsTo(String path) {
        return requests.stream().filter(r -> r.path().equals(path)).toList();
    }

    private void defaults() {
        respond(AngelOneBrokerAdapter.PROFILE, 200, """
                {"status":true,"message":"SUCCESS","errorcode":"","data":{"clientcode":"A123456","name":"TEST USER",
                 "email":"t***@example.com","mobileno":"98****0000","exchanges":["NSE","BSE"],"products":["DELIVERY","INTRADAY"],
                 "lastlogintime":"","brokerid":"B2C"}}""");
        respond(AngelOneBrokerAdapter.LOGOUT, 200, """
                {"status":true,"message":"SUCCESS","errorcode":"","data":""}""");
        respond(AngelOneBrokerAdapter.RMS, 200, """
                {"status":true,"message":"SUCCESS","errorcode":"","data":{"net":"18116.8100","availablecash":"18116.8100",
                 "availableintradaypayin":"0.0000","availablelimitmargin":"-1053.7500","collateral":"0.0000",
                 "m2munrealized":"0.0000","m2mrealized":"0.0000","utiliseddebits":"12554.5500","utilisedspan":"0.0000",
                 "utilisedoptionpremium":"0.0000","utilisedholdingsales":"0.0000","utilisedexposure":"0.0000",
                 "utilisedturnover":"0.0000","utilisedpayout":"0.0000"}}""");
        respond(AngelOneBrokerAdapter.HOLDINGS, 200, """
                {"status":true,"message":"SUCCESS","errorcode":"","data":[
                 {"tradingsymbol":"TATASTEEL-EQ","exchange":"NSE","isin":"INE081A01020","t1quantity":0,"realisedquantity":2,
                  "quantity":2,"authorisedquantity":0,"product":"DELIVERY","collateralquantity":null,"collateraltype":null,
                  "haircut":0,"averageprice":111.87,"ltp":130.15,"symboltoken":"3499","close":129.6,
                  "profitandloss":37,"pnlpercentage":16.34},
                 {"tradingsymbol":"GOLDBEES","exchange":"NSE","isin":"INF204KB17I5","t1quantity":0,"realisedquantity":10,
                  "quantity":10,"authorisedquantity":0,"product":"DELIVERY","collateralquantity":null,"collateraltype":null,
                  "haircut":0,"averageprice":"70.10","ltp":"71.62","symboltoken":"14428","close":71.4,
                  "profitandloss":15,"pnlpercentage":2.17}]}""");
        respond(AngelOneBrokerAdapter.POSITIONS, 200, """
                {"status":true,"message":"SUCCESS","errorcode":"","data":[
                 {"exchange":"NSE","symboltoken":"3045","producttype":"INTRADAY","tradingsymbol":"SBIN-EQ","symbolname":"SBIN",
                  "instrumenttype":"","buyqty":"0","sellqty":"3","buyamount":"0.00","sellamount":"2412.30","netqty":"-3",
                  "avgnetprice":"804.10","netprice":"804.10","netvalue":"2412.30","realised":"0.00","unrealised":"6.30","pnl":"6.30"},
                 {"exchange":"NSE","symboltoken":"2885","producttype":"DELIVERY","tradingsymbol":"RELIANCE-EQ","symbolname":"RELIANCE",
                  "instrumenttype":"","buyqty":"1","sellqty":"0","netqty":"1","avgnetprice":"2900.00","netprice":"2900.00",
                  "realised":"0.00","unrealised":"-12.50"}]}""");
        respond(AngelOneBrokerAdapter.ORDER_BOOK, 200, """
                {"status":true,"message":"SUCCESS","errorcode":"","data":[
                 {"variety":"NORMAL","ordertype":"LIMIT","producttype":"DELIVERY","duration":"DAY","price":"130.00",
                  "triggerprice":"0","quantity":"10","disclosedquantity":"0","squareoff":"0","stoploss":"0","trailingstoploss":"0",
                  "tradingsymbol":"TATASTEEL-EQ","transactiontype":"BUY","exchange":"NSE","symboltoken":"3499",
                  "instrumenttype":"","strikeprice":"-1","optiontype":"","expirydate":"","lotsize":"1","cancelsize":"0",
                  "averageprice":"129.95","filledshares":"4","unfilledshares":"6","orderid":"201020000000080",
                  "text":"","status":"open","orderstatus":"open","updatetime":"08-Nov-2026 10:15:02",
                  "exchtime":"08-Nov-2026 10:15:02","exchorderupdatetime":"08-Nov-2026 10:15:02","fillid":"","filltime":"",
                  "uniqueorderid":"34reqfachdfih"},
                 {"variety":"NORMAL","ordertype":"MARKET","producttype":"INTRADAY","duration":"DAY","price":"0",
                  "quantity":"3","tradingsymbol":"SBIN-EQ","transactiontype":"SELL","exchange":"NSE","averageprice":"0",
                  "filledshares":"0","unfilledshares":"3","orderid":"201020000000081","text":"RMS: margin exceeds",
                  "status":"rejected","orderstatus":"rejected","updatetime":"08-Nov-2026 10:16:40"},
                 {"variety":"NORMAL","ordertype":"STOPLOSS_LIMIT","producttype":"DELIVERY","duration":"DAY","price":"95.00",
                  "quantity":"1","tradingsymbol":"ITC-EQ","transactiontype":"SELL","exchange":"NSE","averageprice":"0",
                  "filledshares":"0","unfilledshares":"1","orderid":"201020000000082","text":"",
                  "status":"after market order req received","updatetime":"08-Nov-2026 16:01:00"}]}""");
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
