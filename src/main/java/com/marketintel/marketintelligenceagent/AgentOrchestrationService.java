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
            - A product-specific filing applies only to the product named in its title. Never present a filing about a different product as relevant to the ingredient asked about.
            - Country-wide actions (Section 301) may or may not cover the ingredient. Say that coverage is not confirmed by this data; never guess whether they apply.
            - If there are no filings that name the ingredient, say "no filing in this data names <ingredient>". Never claim there are no restrictions or no risk: the data does not cover everything.
            - If country-wide actions (Section 301) exist for the country, always mention them, even when no ingredient-specific filing exists.
            - For questions about the risk, duty or cost of an ingredient, call both get_market_events (with the ingredient) and get_tariff_rate.
            - If the user names no country, call get_ingredient_info first and use its origin country; say that you assumed it.
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
                  "description": "Get trade filings for a country. Pass the ingredient whenever the question is about one: the result separates filings that name that ingredient from country-wide Section 301 actions. Call this after get_ingredient_info if you need to assess tariff or market risk.",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "country": {"type": "string", "description": "The EXPORTING country the goods come from, e.g. 'China'. Never 'United States': every filing is a U.S. action on imports."},
                      "ingredient": {"type": "string", "description": "The ingredient the question is about, e.g. 'Glycine'. Optional but strongly recommended."}
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
            JsonNode response = callOpenAiWithTools(messages, "auto");
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
        // Out of tool rounds: one last call with tools disabled, so the model answers from the
        // results it already has instead of the user getting no answer (R19).
        ObjectNode limitNote = jsonMapper.createObjectNode();
        limitNote.put("role", "system");
        limitNote.put("content", "Tool-call limit reached. Answer now using only the tool results above. "
                + "Say plainly what the data does not show; do not guess." + notCheckedNote(toolTrace));
        messages.add(limitNote);
        JsonNode content = callOpenAiWithTools(messages, "none").path("choices").get(0).path("message").path("content");
        String answer = content.isMissingNode() || content.isNull() ? "" : content.asText();
        if (answer.isBlank()) {
            answer = "Agent could not complete the query within the allowed number of tool-call rounds.";
        }
        saveRecommendation(userQuery, answer, toolTrace);
        return new AgentResult(answer, toolTrace);
    }

    // Which kinds of data the model never looked up before the round limit. Named explicitly so
    // the answer says "not checked" instead of "none found" (absence of a lookup is not absence of data).
    private static String notCheckedNote(List<Map<String, Object>> toolTrace) {
        List<String> called = toolTrace.stream().map(t -> String.valueOf(t.get("tool"))).toList();
        List<String> missing = new ArrayList<>();
        if (!called.contains("get_market_events") && !called.contains("search_market_events")) {
            missing.add("trade filings (antidumping, countervailing, Section 301)");
        }
        if (!called.contains("get_tariff_rate")) {
            missing.add("the base tariff rate");
        }
        if (missing.isEmpty()) {
            return "";
        }
        return " These were NOT checked: " + String.join("; ", missing) + ". Tell the user they were not"
                + " checked, and do not say none exist.";
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
          case "get_market_events" -> agentToolService.getMarketEventsAsText(
                  args.path("country").asText(), args.path("ingredient").asText());
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
    // toolChoice: "auto" lets the model call tools; "none" forces a text answer.
    private JsonNode callOpenAiWithTools(ArrayNode messages, String toolChoice) {
        ObjectNode requestBody = jsonMapper.createObjectNode();
        requestBody.put("model", "gpt-4o-mini");
        requestBody.set("messages", messages);
        requestBody.set("tools", jsonMapper.readTree(TOOLS_DEFINITION));
        requestBody.put("tool_choice", toolChoice);
        // Same question -> (nearly) the same tool calls and answer. temperature 0 removes sampling
        // randomness; seed asks OpenAI for best-effort repeatability. Not a hard guarantee.
        requestBody.put("temperature", 0);
        requestBody.put("seed", 42);

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