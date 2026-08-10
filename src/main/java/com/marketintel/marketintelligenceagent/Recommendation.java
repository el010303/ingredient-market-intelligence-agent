package com.marketintel.marketintelligenceagent;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "recommendations")

public class Recommendation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String queryType; // parameter_optimization / candidate_generation
    private String customerContext;
    private String ingredientCategory;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> recommendedOptions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> reasoningTrace;

    private LocalDateTime createdAt = LocalDateTime.now();

    // getters and setters
    public Long getId() { return id; }
    public String getQueryType() { return queryType; }
    public void setQueryType(String queryType) { this.queryType = queryType; }
    public String getCustomerContext() { return customerContext; }
    public void setCustomerContext(String customerContext) { this.customerContext = customerContext; }
    public String getIngredientCategory() { return ingredientCategory; }
    public void setIngredientCategory(String ingredientCategory) { this.ingredientCategory = ingredientCategory; }
    public List<Map<String, Object>> getRecommendedOptions() { return recommendedOptions; }
    public void setRecommendedOptions(List<Map<String, Object>> recommendedOptions) { this.recommendedOptions = recommendedOptions; }
    public Map<String, Object> getReasoningTrace() { return reasoningTrace; }
    public void setReasoningTrace(Map<String, Object> reasoningTrace) { this.reasoningTrace = reasoningTrace; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
