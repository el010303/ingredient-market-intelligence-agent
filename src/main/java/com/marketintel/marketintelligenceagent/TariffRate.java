package com.marketintel.marketintelligenceagent;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/** Latest observed USITC HTS rates for one 10-digit line. Changes over time are recorded as market_events. */
@Entity
@Table(name = "tariff_rates")
public class TariffRate {

    @Id
    @Column(name = "hts_code", length = 20)
    private String htsCode;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String generalRate;

    @Column(columnDefinition = "TEXT")
    private String specialRate;

    private String otherRate;

    private OffsetDateTime fetchedAt;   // last time we checked
    private OffsetDateTime changedAt;   // last time any rate actually changed

    public String getHtsCode() { return htsCode; }
    public void setHtsCode(String htsCode) { this.htsCode = htsCode; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getGeneralRate() { return generalRate; }
    public void setGeneralRate(String generalRate) { this.generalRate = generalRate; }
    public String getSpecialRate() { return specialRate; }
    public void setSpecialRate(String specialRate) { this.specialRate = specialRate; }
    public String getOtherRate() { return otherRate; }
    public void setOtherRate(String otherRate) { this.otherRate = otherRate; }
    public OffsetDateTime getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(OffsetDateTime fetchedAt) { this.fetchedAt = fetchedAt; }
    public OffsetDateTime getChangedAt() { return changedAt; }
    public void setChangedAt(OffsetDateTime changedAt) { this.changedAt = changedAt; }
}
