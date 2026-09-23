package com.srtp.server.model;

import java.time.LocalDateTime;

public class HistoryRecord {
    private Long id;
    private String algorithmId;
    private int cityCount;
    private String inputText;
    private String citiesJson;
    private int seed;
    private int algoSeed;
    private int citySeed;
    private String params;
    private double bestDistance;
    private String bestPath;
    private String bestPathText;
    private int elapsedMs;
    private long complexityScore;
    private String complexityLabel;
    private Long userId;
    private LocalDateTime createdAt;

    public HistoryRecord() {}

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAlgorithmId() { return algorithmId; }
    public void setAlgorithmId(String algorithmId) { this.algorithmId = algorithmId; }
    public int getCityCount() { return cityCount; }
    public void setCityCount(int cityCount) { this.cityCount = cityCount; }
    public String getInputText() { return inputText; }
    public void setInputText(String inputText) { this.inputText = inputText; }
    public String getCitiesJson() { return citiesJson; }
    public void setCitiesJson(String citiesJson) { this.citiesJson = citiesJson; }
    public int getSeed() { return seed; }
    public void setSeed(int seed) { this.seed = seed; }
    public int getAlgoSeed() { return algoSeed; }
    public void setAlgoSeed(int algoSeed) { this.algoSeed = algoSeed; }
    public int getCitySeed() { return citySeed; }
    public void setCitySeed(int citySeed) { this.citySeed = citySeed; }
    public String getParams() { return params; }
    public void setParams(String params) { this.params = params; }
    public double getBestDistance() { return bestDistance; }
    public void setBestDistance(double bestDistance) { this.bestDistance = bestDistance; }
    public String getBestPath() { return bestPath; }
    public void setBestPath(String bestPath) { this.bestPath = bestPath; }
    public String getBestPathText() { return bestPathText; }
    public void setBestPathText(String bestPathText) { this.bestPathText = bestPathText; }
    public int getElapsedMs() { return elapsedMs; }
    public void setElapsedMs(int elapsedMs) { this.elapsedMs = elapsedMs; }
    public long getComplexityScore() { return complexityScore; }
    public void setComplexityScore(long complexityScore) { this.complexityScore = complexityScore; }
    public String getComplexityLabel() { return complexityLabel; }
    public void setComplexityLabel(String complexityLabel) { this.complexityLabel = complexityLabel; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
