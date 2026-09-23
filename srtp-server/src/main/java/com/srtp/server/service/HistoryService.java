package com.srtp.server.service;

import com.srtp.server.model.HistoryRecord;
import com.srtp.server.repository.HistoryRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class HistoryService {

    private final HistoryRepository historyRepo;

    public HistoryService(HistoryRepository historyRepo) {
        this.historyRepo = historyRepo;
    }

    public long insertRecord(HistoryRecord record) {
        return historyRepo.insert(record);
    }

    public List<HistoryRecord> query(String algorithmId, Long recordId, Integer cityCount,
                                      Integer algoSeed, Integer citySeed, String date, Long userId) {
        return historyRepo.findByFilter(algorithmId, recordId, cityCount, algoSeed, citySeed, date, userId);
    }

    public Map<String, Object> deleteRecords(List<Long> ids, Long userId) {
        boolean ok = historyRepo.deleteByIds(ids, userId);
        return Map.of("ok", ok, "deleted", ids.size());
    }

    public List<Long> findIdsByCombo(String algorithmId, Integer algoSeed, Integer citySeed, Long userId) {
        return historyRepo.findIdsByCombo(algorithmId, algoSeed, citySeed, userId);
    }
}
