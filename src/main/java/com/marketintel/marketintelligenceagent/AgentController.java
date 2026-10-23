package com.marketintel.marketintelligenceagent;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AgentController {

    private final AgentOrchestrationService agentOrchestrationService;

    public AgentController(AgentOrchestrationService agentOrchestrationService) {
        this.agentOrchestrationService = agentOrchestrationService;
    }

    @GetMapping("/api/agent/query")
    public AgentResult query(@RequestParam String q) {
        return agentOrchestrationService.handleQuery(q);
    }
}