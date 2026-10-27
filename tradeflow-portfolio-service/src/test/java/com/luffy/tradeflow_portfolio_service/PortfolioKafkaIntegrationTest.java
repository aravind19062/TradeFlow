package com.luffy.tradeflow_portfolio_service;

import com.luffy.tradeflow.common.events.EventCodec;
import com.luffy.tradeflow.common.events.Events;
import com.luffy.tradeflow.common.events.Topics;
import com.luffy.tradeflow_portfolio_service.repository.AccountRepository;
import com.luffy.tradeflow_portfolio_service.repository.ReservationRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Kafka protocol, no Docker: an in-JVM broker, the real listener containers,
 * error handler, DLT recoverer and outbox publisher.
 */
@SpringBootTest(properties = "tradeflow.test.kafka=true")
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 3, topics = {
        Topics.USERS_REGISTERED, Topics.ORDERS_PLACED, Topics.ORDERS_RESERVED, Topics.ORDERS_REJECTED,
        Topics.TRADES_EXECUTED, Topics.TRADES_EXECUTED + Topics.DLT_SUFFIX, Topics.ORDERS_CANCELLED})
class PortfolioKafkaIntegrationTest {

    private static final long USER = 501;

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired EventCodec codec;
    @Autowired AccountRepository accounts;
    @Autowired ReservationRepository reservations;
    @Autowired EmbeddedKafkaBroker broker;

    private KafkaConsumer<String, String> consumer;

    @BeforeEach
    void consumer() {
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(Topics.ORDERS_RESERVED, Topics.TRADES_EXECUTED + Topics.DLT_SUFFIX));
    }

    @AfterEach
    void close() {
        consumer.close();
    }

    @Test
    void sagaRepliesDuplicatesAndDeadLetters() throws Exception {
        send(Topics.USERS_REGISTERED, new Events.UserRegistered(USER, "k@test.dev", "Kafka"));
        await(() -> accounts.existsById(USER), "account opened from users.registered");

        UUID orderId = UUID.randomUUID();
        String placed = codec.encode(codec.wrap(Topics.ORDERS_PLACED, String.valueOf(USER),
                new Events.OrderPlaced(orderId, USER, "TCS", Events.OrderSide.BUY, Events.OrderType.MARKET, 2,
                        new BigDecimal("100.00"))));
        kafka.send(Topics.ORDERS_PLACED, String.valueOf(USER), placed).get();
        kafka.send(Topics.ORDERS_PLACED, String.valueOf(USER), placed).get(); // redelivery

        // Other test classes share this JVM's in-memory DB, so only look at replies for this order.
        List<ConsumerRecord<String, String>> reserved = collect(Topics.ORDERS_RESERVED,
                r -> orderId.toString().equals(r.key()), 1, Duration.ofSeconds(15));
        assertThat(reserved).singleElement().satisfies(r -> {
            assertThat(r.key()).isEqualTo(orderId.toString());
            assertThat(r.value()).contains("\"reservedCash\":200.00");
        });
        assertThat(reservations.findById(orderId)).isPresent();
        assertThat(accounts.findById(USER).orElseThrow().getReservedCash()).isEqualByComparingTo("200.00");

        kafka.send(Topics.TRADES_EXECUTED, "x", "{this is not json").get();
        kafka.send(Topics.TRADES_EXECUTED, String.valueOf(USER), codec.encode(codec.wrap(Topics.TRADES_EXECUTED,
                String.valueOf(USER), new Events.TradeExecuted(UUID.randomUUID(), UUID.randomUUID(), USER, "TCS",
                        Events.OrderSide.BUY, 1, BigDecimal.TEN, new BigDecimal("10.00"), Instant.now())))).get();

        List<ConsumerRecord<String, String>> dead = collect(Topics.TRADES_EXECUTED + Topics.DLT_SUFFIX, r -> true, 2,
                Duration.ofSeconds(20));
        assertThat(dead).hasSize(2);
        assertThat(dead).extracting(this::exceptionMessage)
                .anySatisfy(m -> assertThat(m).contains("Malformed event JSON"))
                .anySatisfy(m -> assertThat(m).contains("has no reservation"));
        // Neither bad message changed the account.
        assertThat(accounts.findById(USER).orElseThrow().getCashBalance()).isEqualByComparingTo("1000000.00");
    }

    private void send(String topic, Object payload) throws Exception {
        kafka.send(topic, String.valueOf(USER), codec.encode(codec.wrap(topic, String.valueOf(USER), payload))).get();
    }

    private String exceptionMessage(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader("kafka_dlt-exception-message");
        return header == null ? "" : new String(header.value(), StandardCharsets.UTF_8);
    }

    private List<ConsumerRecord<String, String>> collect(String topic, Predicate<ConsumerRecord<String, String>> filter,
                                                         int expected, Duration timeout) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            poll(topic, filter, records);
            if (records.size() >= expected) {
                // keep polling briefly to catch unexpected extras (e.g. a duplicate reply)
                long settle = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                while (System.nanoTime() < settle) {
                    poll(topic, filter, records);
                }
                break;
            }
        }
        return records;
    }

    private void poll(String topic, Predicate<ConsumerRecord<String, String>> filter,
                      List<ConsumerRecord<String, String>> into) {
        for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(250)).records(topic)) {
            if (filter.test(r)) {
                into.add(r);
            }
        }
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            Thread.sleep(100);
        }
    }
}
