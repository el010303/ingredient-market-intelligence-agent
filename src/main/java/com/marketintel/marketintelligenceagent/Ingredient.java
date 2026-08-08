package com.marketintel.marketintelligenceagent;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.Map;

@Entity
@Table(name = "ingredients")
public class Ingredient {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String category;

    @Column(name = "origin_country")
    private String originCountry;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> specs;

    @Column(name = "preferred_manufacturer")
    private String preferredManufacturer;

    public Long getId() {return id;}
    public String getName() {return name;}
    public void setName(String name) {this.name = name;}
    public String getCategory() {return category;}
    public void setCategory(String category) {this.category = category;}
    public String getOriginCountry() {return originCountry;}
    public void setOriginCountry(String originCountry) {this.originCountry = originCountry;}
    public Map<String, Object> getSpecs() {return specs;}
    public void setSpecs(Map<String, Object> specs) {this.specs = specs;}
    public String getPreferredManufacturer() {return preferredManufacturer;}
    public void setPreferredManufacturer(String preferredManufacturer) {this.preferredManufacturer = preferredManufacturer;}

}
