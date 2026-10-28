package com.luffy.tradeflow.common.kafka;

import com.luffy.tradeflow.common.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ADMIN: inspect and replay dead-lettered records of this service's consumers.
 * Mounted under each service's admin prefix (tradeflow.admin.path), e.g.
 * /api/admin/portfolio/dlt/trades.executed, so the gateway can route it.
 */
@RestController
@RequestMapping("${tradeflow.admin.path:/api/admin}/dlt")
@Tag(name = "Admin: dead letters")
public class DeadLetterAdminController {

    private final DeadLetterService deadLetters;

    public DeadLetterAdminController(DeadLetterService deadLetters) {
        this.deadLetters = deadLetters;
    }

    @GetMapping("/{topic}")
    @Operation(summary = "Dead-lettered records for a topic, oldest first, with the failure reason")
    public List<DeadLetterService.DeadLetter> list(@PathVariable String topic,
                                                   @RequestParam(defaultValue = "20") int limit) {
        return deadLetters.peek(topic, limit);
    }

    @PostMapping("/{topic}/replay")
    @Operation(summary = "Re-publish one dead-lettered record (by partition and offset) to its original topic")
    public DeadLetterService.ReplayResult replay(AuthenticatedUser admin, @PathVariable String topic,
                                                 @RequestParam int partition, @RequestParam long offset) {
        return deadLetters.replay(topic, partition, offset, "user " + admin.id());
    }
}
