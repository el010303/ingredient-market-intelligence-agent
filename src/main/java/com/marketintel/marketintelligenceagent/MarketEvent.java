package com.marketintel.marketintelligenceagent;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "market_events")
public class MarketEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String eventType; // tariff_policy / supply_disruption / general_news
    private String country;
    private String ingredientCategory;
    
    @Column(columnDefinition = "TEXT")
    private String title;

    @Column(columnDefinition = "TEXT")
    private String rawText;

    @Column(columnDefinition = "TEXT")
    private String llmSummary;

    private String sourceUrl;
    private LocalDate eventDate;
    private Double oldRate;
    private Double newRate;
    private Double extractOldRate;
    private Double extractNewRate;
    private LocalDate effectiveDate;

    // getters and setters
    public Long getId() {return id; }
    public String getEventType() {return eventType; }
    public void setEventType(String eventType) {this.eventType = eventType; }
    public String getCountry() {return country; }
    public void setCountry(String country) { this.country = country; }
    public String getIngredientCategory() { return ingredientCategory; }
    public void setIngredientCategory(String ingredientCategory) { this.ingredientCategory = ingredientCategory; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getRawText() { return rawText; }
    public void setRawText(String rawText) { this.rawText = rawText; }
    public String getLlmSummary() { return llmSummary; }
    public void setLlmSummary(String llmSummary) { this.llmSummary = llmSummary; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public LocalDate getEventDate() { return eventDate; }
    public void setEventDate(LocalDate eventDate) { this.eventDate = eventDate; }
    public Double getOldRate() { return oldRate; }
    public void setOldRate(Double oldRate) { this.oldRate = oldRate; }
    public Double getNewRate() { return newRate; }
    public void setNewRate(Double newRate) { this.newRate = newRate; }
    public Double getExtractOldRate() { return extractOldRate; }
    public void setExtractOldRate(Double extractOldRate) {this.extractOldRate = extractOldRate; }
    public Double getExtractNewRate() { return extractNewRate; }
    public void setExtractNewRate(Double extractNewRate) { this.extractNewRate = extractNewRate; }
    public LocalDate getEffectiveDate() { return effectiveDate; }
    public void setEffectiveDate(LocalDate effectiveDate) { this.effectiveDate = effectiveDate; }
}
