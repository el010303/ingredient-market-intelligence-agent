package com.marketintel.marketintelligenceagent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

@Service
public class AgentOrchestrationService {

    @Value("${openai.api.key}")
    private String openaiApiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final AgentToolService agentToolService;

    private final RecommendationRepository recommendationRepository;

    private static final int MAX_ROUNDS = 4;

    // Grounding rules: answer only from tool results, cite every filing with its source link,
    // and reason about recency explicitly (filings can be superseded by later ones in the same case).
    private static final String SYSTEM_PROMPT = """
            You are a trade-policy analyst helping an ingredient buyer assess sourcing risk.
            Today's date is %s.

            Rules:
            - Use ONLY facts returned by the tools. If the tools return nothing relevant, say so plainly.
            - For every filing you mention, include its publication date and its source as a markdown link: [title](url).
            - Lead with the most recent filings. If an older filing in the same case has been followed by a newer one, say that the newer one supersedes it.
            - Do not invent tariff rates, dates, outcomes, prices or trends that are not in the tool results.
            - For duty or cost questions, state the base (MFN) duty and any antidumping/countervailing duties separately; never add them up unless both rates are given.
            - End with a short "Bottom line" for the buyer.
            """;

    private static final String TOOLS_DEFINITION = """
            [
              {
                "type": "function",
                "function": {
                  "name": "get_ingredient_info",
                  "description": "Get information about an ingredient by name, including its origin country and specs",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "name": {"type": "string", "description": "The ingredient name, e.g. 'Maltitol Syrup'"}
                    },
                    "required": ["name"]
                  }
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "get_market_events",
                  "description": "Get tariff and market events for a given country. Call this after get_ingredient_info if you need to assess tariff or market risk.",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "country": {"type": "string", "description": "The country name, e.g. 'China'"}
                    },
                    "required": ["country"]
                  }
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "get_tariff_rate",
                  "description": "Get the U.S. base (general/MFN) import duty for an ingredient from the USITC Harmonized Tariff Schedule. Use this for questions about total duty or cost; combine it with antidumping/countervailing filings from the market-event tools.",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "ingredient": {"type": "string", "description": "The ingredient name, e.g. 'Glycine'"}
                    },
                    "required": ["ingredient"]
                  }
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "search_market_events",
                  "description": "Semantic search over trade and tariff events by topic. Use this for open-ended questions that are not tied to one specific country.",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "query": {"type": "string", "description": "A natural-language description of the topic to search for"}
                    },
                    "required": ["query"]
                  }
                }
              }
            ]
            """;

    public AgentOrchestrationService(AgentToolService agentToolService, RecommendationRepository recommendationRepository) {
        this.agentToolService = agentToolService;
        this.recommendationRepository = recommendationRepository;
    }

    public AgentResult handleQuery(String userQuery) {
        List<Map<String, Object>> toolTrace = new ArrayList<>();
        ArrayNode messages = jsonMapper.createArrayNode();
        ObjectNode systemMessage = jsonMapper.createObjectNode();
        systemMessage.put("role", "system");
        systemMessage.put("content", SYSTEM_PROMPT.formatted(LocalDate.now()));
        messages.add(systemMessage);
        ObjectNode userMessage = jsonMapper.createObjectNode();
        userMessage.put("role", "user");
        userMessage.put("content", userQuery);
        messages.add(userMessage);

        for (int round = 0; round < MAX_ROUNDS; round++) {
            JsonNode response = callOpenAiWithTools(messages);
            JsonNode message = response.path("choices").get(0).path("message");
            JsonNode toolCalls = message.path("tool_calls");

            System.out.println("=== Round " + round + " ===");
            System.out.println("Tool calls: " + toolCalls.toString());
            System.out.println("Content: " + message.path("content").asText());

            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                String answer = message.path("content").asText();
                saveRecommendation(userQuery, answer, toolTrace);
                return new AgentResult(answer, toolTrace);
            }
            
            messages.add(message);

            for (JsonNode call : toolCalls) {
                String toolCallId = call.path("id").asText();
                String functionName = call.path("function").path("name").asText();
                String argsJson = call.path("function").path("arguments").asText();
                JsonNode args = jsonMapper.readTree(argsJson);

                String result = executeToolCall(functionName, args);
                Map<String, Object> traceEntry = new LinkedHashMap<>();
                traceEntry.put("round", round);
                traceEntry.put("tool", functionName);
                traceEntry.put("arguments", argsJson);
                // First part of what the tool returned: the "supporting data" behind the answer
                traceEntry.put("resultPreview", result.length() > 300 ? result.substring(0, 300) + "..." : result);
                toolTrace.add(traceEntry);

                ObjectNode toolMessage = jsonMapper.createObjectNode();
                toolMessage.put("role", "tool");
                toolMessage.put("tool_call_id", toolCallId);
                toolMessage.put("content", result);
                messages.add(toolMessage);
            }
        }
        return new AgentResult(
                "Agent could not complete the query within the allowed number of tool-call rounds.", toolTrace);
    }

    private String executeToolCall(String functionName, JsonNode args) {
      // A failing tool must not fail the whole request: hand the error to the model as the tool
      // result so it can tell the user that part of the data is unavailable.
      try {
        return dispatchTool(functionName, args);
      } catch (RuntimeException e) {
        System.out.println("Tool " + functionName + " failed: " + e.getMessage());
        return "Tool error: " + functionName + " is temporarily unavailable. "
            + "Tell the user this part of the data could not be retrieved; do not guess it.";
      }
    }

    private String dispatchTool(String functionName, JsonNode args) {
      return switch (functionName) {
          case "get_ingredient_info" -> agentToolService.getIngredientInfoAsText(args.path("name").asText());
          case "get_market_events" -> agentToolService.getMarketEventsAsText(args.path("country").asText());
          case "search_market_events" -> agentToolService.searchMarketEventsAsText(args.path("query").asText());
          case "get_tariff_rate" -> agentToolService.getTariffRateAsText(args.path("ingredient").asText());
          default -> "Unknown tool: " + functionName;
      };
    }

    private void saveRecommendation(String userQuery, String answer, List<Map<String, Object>> toolTrace) {
        Recommendation rec = new Recommendation();
        rec.setQueryType("parameter_optimization");
        rec.setCustomerContext(userQuery);
        rec.setRecommendedOptions(List.of(Map.of("answer", answer)));
        rec.setReasoningTrace(Map.of("tool_calls", toolTrace));
        recommendationRepository.save(rec);
    }
    private JsonNode callOpenAiWithTools(ArrayNode messages) {
        ObjectNode requestBody = jsonMapper.createObjectNode();
        requestBody.put("model", "gpt-4o-mini");
        requestBody.set("messages", messages);
        requestBody.set("tools", jsonMapper.readTree(TOOLS_DEFINITION));
        requestBody.put("tool_choice", "auto");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(openaiApiKey);

        HttpEntity<String> request = new HttpEntity<>(requestBody.toString(), headers);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "https://api.openai.com/v1/chat/completions", request, String.class);
        
        String body = response.getBody();
        if (body.trim().startsWith("```")) {
            body = body.trim().replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("```\\s*$", "");
        }
        return jsonMapper.readTree(body);
    }

    
}