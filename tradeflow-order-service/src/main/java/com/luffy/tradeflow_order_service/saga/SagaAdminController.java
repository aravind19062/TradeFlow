package com.luffy.tradeflow_order_service.saga;

import com.luffy.tradeflow.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** ADMIN: inspect sagas and retry failed ones (controlled recovery). */
@RestController
@RequestMapping("/api/admin/orders/sagas")
@Tag(name = "Admin: order sagas")
public class SagaAdminController {

    private final OrderSagaRepository repository;
    private final OrderSagaService sagaService;

    public SagaAdminController(OrderSagaRepository repository, OrderSagaService sagaService) {
        this.repository = repository;
        this.sagaService = sagaService;
    }

    public record SagaView(UUID orderId, SagaState state, Instant deadlineAt, int redriveCount, String lastError,
                           Instant updatedAt) {
        static SagaView from(OrderSaga s) {
            return new SagaView(s.getOrderId(), s.getState(), s.getDeadlineAt(), s.getRedriveCount(), s.getLastError(),
                    s.getUpdatedAt());
        }
    }

    @GetMapping
    @Operation(summary = "Sagas in a given state (default: SETTLEMENT_FAILED)")
    public PageResponse<SagaView> list(@RequestParam(defaultValue = "SETTLEMENT_FAILED") SagaState state,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(repository.findByState(state, PageRequest.of(page, Math.min(size, 100),
                Sort.by(Sort.Direction.DESC, "updatedAt"))), SagaView::from);
    }

    @PostMapping("/{orderId}/retry")
    @Transactional
    @Operation(summary = "Retry a FAILED saga with a fresh re-drive budget (after fixing the root cause)")
    public SagaView retry(@PathVariable UUID orderId) {
        return SagaView.from(sagaService.retry(orderId));
    }
}
