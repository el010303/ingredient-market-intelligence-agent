package com.marketintel.marketintelligenceagent;

import java.util.List;
import java.util.Map;

/** Answer plus the full tool-call trace, so a UI can show how the answer was produced. */
public record AgentResult(String answer, List<Map<String, Object>> toolCalls) {
}
