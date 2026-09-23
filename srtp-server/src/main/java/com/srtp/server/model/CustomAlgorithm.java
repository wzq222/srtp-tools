package com.srtp.server.model;

import java.time.LocalDateTime;

public class CustomAlgorithm {
    private Long id;
    private Long userId;
    private String algoId;
    private String label;
    private String code;
    private String paramSpecs;
    private String iterKey;
    private String popKey;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getAlgoId() { return algoId; }
    public void setAlgoId(String algoId) { this.algoId = algoId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getParamSpecs() { return paramSpecs; }
    public void setParamSpecs(String paramSpecs) { this.paramSpecs = paramSpecs; }
    public String getIterKey() { return iterKey; }
    public void setIterKey(String iterKey) { this.iterKey = iterKey; }
    public String getPopKey() { return popKey; }
    public void setPopKey(String popKey) { this.popKey = popKey; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
