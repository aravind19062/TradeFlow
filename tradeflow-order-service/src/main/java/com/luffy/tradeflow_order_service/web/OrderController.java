package com.luffy.tradeflow_order_service.web;

import com.luffy.tradeflow.common.events.Events.OrderSide;
import com.luffy.tradeflow.common.security.AuthenticatedUser;
import com.luffy.tradeflow.common.web.PageResponse;
import com.luffy.tradeflow_order_service.domain.OrderStatus;
import com.luffy.tradeflow_order_service.service.OrderService;
import com.luffy.tradeflow_order_service.web.OrderDtos.OrderDetailResponse;
import com.luffy.tradeflow_order_service.web.OrderDtos.OrderResponse;
import com.luffy.tradeflow_order_service.web.OrderDtos.PlaceOrderRequest;
import com.luffy.tradeflow_order_service.web.OrderDtos.TradeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

@RestController
@Tag(name = "Orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/api/orders")
    @Operation(summary = "Place an order. 202 + PENDING for a new order; 200 when clientOrderId replays one")
    public ResponseEntity<OrderResponse> place(AuthenticatedUser user, @Valid @RequestBody PlaceOrderRequest request) {
        OrderService.PlacementResult result = orderService.place(user, request);
        if (!result.created()) {
            return ResponseEntity.ok(result.order());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/orders/" + result.order().id()))
                .body(result.order());
    }

    @GetMapping("/api/orders/{id}")
    @Operation(summary = "Order with its status history and executions")
    public OrderDetailResponse get(AuthenticatedUser user, @PathVariable UUID id) {
        return orderService.get(user, id);
    }

    @GetMapping("/api/orders")
    @Operation(summary = "My orders, newest first, with optional filters")
    public PageResponse<OrderResponse> list(AuthenticatedUser user,
                                            @RequestParam(required = false) OrderStatus status,
                                            @RequestParam(required = false) String symbol,
                                            @RequestParam(required = false) OrderSide side,
                                            @RequestParam(required = false)
                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                            @RequestParam(required = false)
                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(orderService.list(user, status, symbol, side, from, to, page, size),
                OrderResponse::from);
    }

    @PostMapping("/api/orders/{id}/cancel")
    @Operation(summary = "Cancel an OPEN order (releases its reservation)")
    public OrderResponse cancel(AuthenticatedUser user, @PathVariable UUID id) {
        return orderService.cancel(user, id);
    }

    @GetMapping("/api/trades")
    @Operation(summary = "My executions, newest first")
    public PageResponse<TradeResponse> trades(AuthenticatedUser user,
                                              @RequestParam(required = false) String symbol,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(orderService.trades(user, symbol, page, size), TradeResponse::from);
    }
}
