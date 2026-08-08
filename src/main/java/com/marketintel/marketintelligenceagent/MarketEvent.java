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

    private String sourceUrl;
    private LocalDate eventDate;
    private Double oldRate;
    private Double newRate;

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
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public LocalDate getEventDate() { return eventDate; }
    public void setEventDate(LocalDate eventDate) { this.eventDate = eventDate; }
    public Double getOldRate() { return oldRate; }
    public void setOldRate(Double oldRate) { this.oldRate = oldRate; }
    public Double getNewRate() { return newRate; }
    public void setNewRate(Double newRate) { this.newRate = newRate; }
}
