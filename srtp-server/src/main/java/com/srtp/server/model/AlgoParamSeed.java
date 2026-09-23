package com.srtp.server.model;

import java.time.LocalDateTime;

public class AlgoParamSeed {
    private Long id;
    private Long userId;
    private String algorithmId;
    private int seedNumber;
    private String params;
    private LocalDateTime createdAt;

    public AlgoParamSeed() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getAlgorithmId() { return algorithmId; }
    public void setAlgorithmId(String algorithmId) { this.algorithmId = algorithmId; }
    public int getSeedNumber() { return seedNumber; }
    public void setSeedNumber(int seedNumber) { this.seedNumber = seedNumber; }
    public String getParams() { return params; }
    public void setParams(String params) { this.params = params; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
