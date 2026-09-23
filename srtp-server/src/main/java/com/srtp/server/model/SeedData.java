package com.srtp.server.model;

import java.time.LocalDateTime;

public class SeedData {
    private Long id;
    private Long userId;
    private int seed;
    private int cityCount;
    private String cityData;
    private String weights;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public int getSeed() { return seed; }
    public void setSeed(int seed) { this.seed = seed; }
    public int getCityCount() { return cityCount; }
    public void setCityCount(int cityCount) { this.cityCount = cityCount; }
    public String getCityData() { return cityData; }
    public void setCityData(String cityData) { this.cityData = cityData; }
    public String getWeights() { return weights; }
    public void setWeights(String weights) { this.weights = weights; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
