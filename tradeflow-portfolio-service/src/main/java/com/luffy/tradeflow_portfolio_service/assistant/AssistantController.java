package com.luffy.tradeflow_portfolio_service.assistant;

import com.luffy.tradeflow.common.security.AuthenticatedUser;
import com.luffy.tradeflow_portfolio_service.assistant.AssistantModels.AskRequest;
import com.luffy.tradeflow_portfolio_service.assistant.AssistantModels.AskResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/assistant")
@Tag(name = "AI portfolio assistant")
public class AssistantController {

    private final AssistantService assistantService;

    public AssistantController(AssistantService assistantService) {
        this.assistantService = assistantService;
    }

    @PostMapping("/ask")
    @Operation(summary = "Ask about your own portfolio, risk, performance, watchlist or orders (read-only)")
    public AskResponse ask(AuthenticatedUser user, @Valid @RequestBody AskRequest request) {
        return assistantService.ask(user, request.question());
    }

    @GetMapping("/capabilities")
    @Operation(summary = "Configured provider, available tools and example questions")
    public Map<String, Object> capabilities() {
        return assistantService.capabilities();
    }
}
