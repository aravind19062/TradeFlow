package com.luffy.tradeflow.e2e;

import com.luffy.tradeflow_market_data_service.TradeflowMarketDataServiceApplication;
import com.luffy.tradeflow_order_service.TradeflowOrderServiceApplication;
import com.luffy.tradeflow_portfolio_service.TradeflowPortfolioServiceApplication;
import com.luffy.tradeflow_user_service.TradeflowUserServiceApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the four real services in one JVM (H2 in-memory databases, one embedded
 * Kafka broker) and drives the complete workflow through their HTTP APIs:
 * register → account opened → orders → reservation → execution → settlement →
 * analytics → reconciliation. Nothing is mocked.
 */
@EmbeddedKafka(partitions = 3)
class TradeFlowEndToEndTest {

    private static final String SECRET = "e2e-only-jwt-secret-0123456789-abcdefghij";
    private static final String DEMO_PASSWORD = "E2e@12345";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final RestClient HTTP = RestClient.create();

    private static ConfigurableApplicationContext market;
    private static ConfigurableApplicationContext users;
    private static ConfigurableApplicationContext orders;
    private static ConfigurableApplicationContext portfolio;
    private static String userUrl;
    private static String marketUrl;
    private static String orderUrl;
    private static String portfolioUrl;

    @BeforeAll
    static void startServices(EmbeddedKafkaBroker broker) {
        String kafka = broker.getBrokersAsString();
        market = start(TradeflowMarketDataServiceApplication.class, "tradeflow-market-data-service", "market", kafka,
                Map.of("tradeflow.market.simulation.enabled", "false"));
        marketUrl = url(market);
        users = start(TradeflowUserServiceApplication.class, "tradeflow-user-service", "user", kafka,
                Map.of("tradeflow.demo.enabled", "true", "tradeflow.demo.password", DEMO_PASSWORD));
        userUrl = url(users);
        orders = start(TradeflowOrderServiceApplication.class, "tradeflow-order-service", "order", kafka,
                Map.of("tradeflow.orders.market-data-url", marketUrl));
        orderUrl = url(orders);
        portfolio = start(TradeflowPortfolioServiceApplication.class, "tradeflow-portfolio-service", "portfolio",
                kafka, Map.of("tradeflow.portfolio.market-data-url", marketUrl,
                        "tradeflow.portfolio.order-service-url", orderUrl));
        portfolioUrl = url(portfolio);
    }

    @AfterAll
    static void stopServices() {
        for (ConfigurableApplicationContext ctx : new ConfigurableApplicationContext[]{portfolio, orders, users, market}) {
            if (ctx != null) {
                ctx.close();
            }
        }
    }

    @Test
    void orderToTradeToPortfolio() throws Exception {
        // 1. Register; portfolio-service opens the account from users.registered.
        JsonNode registered = call(HttpMethod.POST, userUrl + "/api/auth/register", null,
                "{\"name\":\"E2E Trader\",\"email\":\"e2e@test.dev\",\"password\":\"Secret#123\"}", 201);
        String token = registered.get("accessToken").asString();
        JsonNode summary = await("account opened",
                () -> callStatus(HttpMethod.GET, portfolioUrl + "/api/portfolio/summary", token, null),
                r -> r.status == 200).body;
        assertThat(summary.get("cashBalance").decimalValue()).isEqualByComparingTo("1000000.00");

        // 2. MARKET BUY fills at the accepted quote; cash and holdings settle exactly.
        JsonNode buy = call(HttpMethod.POST, orderUrl + "/api/orders", token,
                "{\"symbol\":\"TCS\",\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":10,\"clientOrderId\":\"e2e-buy\"}",
                202);
        JsonNode filled = awaitOrder(token, buy.get("id").asString(), "FILLED");
        BigDecimal gross = filled.at("/trades/0/grossAmount").decimalValue();
        BigDecimal buyPrice = filled.at("/trades/0/price").decimalValue();
        assertThat(gross).isEqualByComparingTo(buy.get("reservationPrice").decimalValue().multiply(BigDecimal.TEN));
        JsonNode afterBuy = await("buy settled",
                () -> callStatus(HttpMethod.GET, portfolioUrl + "/api/portfolio/summary", token, null),
                r -> r.body.get("holdings").size() == 1).body;
        assertThat(afterBuy.get("cashBalance").decimalValue())
                .isEqualByComparingTo(new BigDecimal("1000000.00").subtract(gross));
        assertThat(afterBuy.at("/holdings/0/quantity").asLong()).isEqualTo(10);

        // 3. Funds and position checks reject impossible orders.
        JsonNode oversell = call(HttpMethod.POST, orderUrl + "/api/orders", token,
                "{\"symbol\":\"TCS\",\"side\":\"SELL\",\"type\":\"MARKET\",\"quantity\":11}", 202);
        assertThat(awaitOrder(token, oversell.get("id").asString(), "REJECTED").at("/order/rejectCode").asString())
                .isEqualTo("INSUFFICIENT_POSITION");
        JsonNode tooExpensive = call(HttpMethod.POST, orderUrl + "/api/orders", token,
                "{\"symbol\":\"MARUTI\",\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":1000}", 202);
        assertThat(awaitOrder(token, tooExpensive.get("id").asString(), "REJECTED").at("/order/rejectCode").asString())
                .isEqualTo("INSUFFICIENT_FUNDS");

        // 4. A resting LIMIT order holds cash until cancelled, then releases it.
        JsonNode resting = call(HttpMethod.POST, orderUrl + "/api/orders", token,
                "{\"symbol\":\"INFY\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":5,\"limitPrice\":1.00}", 202);
        awaitOrder(token, resting.get("id").asString(), "OPEN");
        assertThat(call(HttpMethod.GET, portfolioUrl + "/api/portfolio/summary", token, null, 200)
                .get("reservedCash").decimalValue()).isEqualByComparingTo("5.00");
        call(HttpMethod.POST, orderUrl + "/api/orders/" + resting.get("id").asString() + "/cancel", token, null, 200);
        await("reservation released",
                () -> callStatus(HttpMethod.GET, portfolioUrl + "/api/portfolio/summary", token, null),
                r -> r.body.get("reservedCash").decimalValue().signum() == 0);

        // 5. SELL realizes P&L against the average cost.
        JsonNode sell = call(HttpMethod.POST, orderUrl + "/api/orders", token,
                "{\"symbol\":\"TCS\",\"side\":\"SELL\",\"type\":\"MARKET\",\"quantity\":4}", 202);
        BigDecimal sellPrice = awaitOrder(token, sell.get("id").asString(), "FILLED").at("/trades/0/price")
                .decimalValue();
        BigDecimal expectedRealized = sellPrice.subtract(buyPrice).multiply(BigDecimal.valueOf(4));
        JsonNode afterSell = await("sell settled",
                () -> callStatus(HttpMethod.GET, portfolioUrl + "/api/portfolio/summary", token, null),
                r -> r.body.at("/holdings/0/quantity").asLong() == 6).body;
        assertThat(afterSell.get("realizedPnl").decimalValue()).isEqualByComparingTo(expectedRealized);

        // 6. Admin moves the simulated market; analytics and reconciliation stay consistent.
        String admin = call(HttpMethod.POST, userUrl + "/api/auth/login", null,
                "{\"email\":\"admin@tradeflow.dev\",\"password\":\"" + DEMO_PASSWORD + "\"}", 200)
                .get("accessToken").asString();
        call(HttpMethod.POST, marketUrl + "/api/market/simulation/advance?ticks=5", admin, null, 200);
        JsonNode performance = call(HttpMethod.GET, portfolioUrl + "/api/portfolio/performance", token, null, 200);
        assertThat(performance.get("pointCount").asInt()).isGreaterThanOrEqualTo(4);
        JsonNode risk = call(HttpMethod.GET, portfolioUrl + "/api/portfolio/risk", token, null, 200);
        assertThat(risk.get("largestPosition").asString()).isEqualTo("TCS");
        JsonNode reconciliation = call(HttpMethod.GET, portfolioUrl + "/api/admin/reconciliation", admin, null, 200);
        assertThat(reconciliation.get("consistent").asBoolean()).isTrue();
        assertThat(reconciliation.get("accountsChecked").asInt()).isGreaterThanOrEqualTo(3);

        // 7. Another user cannot read this user's order.
        String demo = call(HttpMethod.POST, userUrl + "/api/auth/login", null,
                "{\"email\":\"demo@tradeflow.dev\",\"password\":\"" + DEMO_PASSWORD + "\"}", 200)
                .get("accessToken").asString();
        call(HttpMethod.GET, orderUrl + "/api/orders/" + buy.get("id").asString(), demo, null, 404);
    }

    // ------------------------------------------------------------------ helpers

    private static ConfigurableApplicationContext start(Class<?> app, String name, String module, String kafka,
                                                        Map<String, String> extra) {
        Map<String, Object> props = new HashMap<>();
        // Do not load any application.properties: all four jars share one classpath here.
        props.put("spring.config.name", "tradeflow-e2e-none");
        props.put("spring.application.name", name);
        props.put("spring.main.banner-mode", "off");
        props.put("server.port", "0");
        props.put("spring.datasource.url", "jdbc:h2:mem:e2e-" + module
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        props.put("spring.datasource.username", "sa");
        props.put("spring.datasource.password", "");
        props.put("spring.jpa.hibernate.ddl-auto", "validate");
        props.put("spring.jpa.open-in-view", "false");
        props.put("spring.flyway.locations", "classpath:db/migration/common,classpath:db/migration/" + module);
        props.put("spring.kafka.bootstrap-servers", kafka);
        props.put("spring.kafka.consumer.group-id", name);
        props.put("spring.kafka.consumer.auto-offset-reset", "earliest");
        props.put("spring.kafka.listener.ack-mode", "record");
        props.put("spring.kafka.producer.acks", "all");
        props.put("tradeflow.security.jwt.secret", SECRET);
        props.put("tradeflow.security.jwt.issuer", "tradeflow-user-service");
        props.put("tradeflow.outbox.poll-interval-ms", "100");
        props.put("tradeflow.kafka.retry.interval-ms", "200");
        props.put("tradeflow.kafka.retry.max-attempts", "2");
        props.putAll(extra);
        return new SpringApplicationBuilder(app).properties(props).run();
    }

    private static String url(ConfigurableApplicationContext ctx) {
        return "http://localhost:" + ctx.getEnvironment().getProperty("local.server.port");
    }

    private record Response(int status, JsonNode body) {
    }

    private static Response callStatus(HttpMethod method, String url, String token, String body) {
        return HTTP.method(method).uri(url)
                .headers(h -> {
                    if (token != null) {
                        h.setBearerAuth(token);
                    }
                    h.setContentType(MediaType.APPLICATION_JSON);
                })
                .body(body == null ? "" : body)
                .exchange((request, response) -> {
                    String text = new String(response.getBody().readAllBytes());
                    return new Response(response.getStatusCode().value(),
                            text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text));
                });
    }

    private static JsonNode call(HttpMethod method, String url, String token, String body, int expectedStatus) {
        Response r = callStatus(method, url, token, body);
        assertThat(r.status).as("%s %s -> %s", method, url, r.body).isEqualTo(expectedStatus);
        return r.body;
    }

    private static JsonNode awaitOrder(String token, String orderId, String status) throws InterruptedException {
        return await("order " + orderId + " " + status,
                () -> callStatus(HttpMethod.GET, orderUrl + "/api/orders/" + orderId, token, null),
                r -> r.status == 200 && status.equals(r.body.at("/order/status").asString())).body;
    }

    private static Response await(String what, java.util.function.Supplier<Response> call, Predicate<Response> done)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        Response last = null;
        while (System.nanoTime() < deadline) {
            last = call.get();
            if (done.test(last)) {
                return last;
            }
            Thread.sleep(150);
        }
        throw new AssertionError("Timed out waiting for " + what + "; last response: " + (last == null ? null
                : last.status + " " + last.body));
    }
}
