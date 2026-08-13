package com.richard.fyoung.customerwork.capability.csat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内 CSAT 存储（默认实现，离线可测）。
 *
 * <p>重启即清空，趋势无从谈起；生产切 {@code csat.store-mode=jdbc}。</p>
 * @author owlzhangfq@gmail.com
 */
public class InMemoryCsatStore implements CsatStore {

    private final Map<String, CsatSurvey> surveys = new ConcurrentHashMap<>();

    @Override
    public void save(CsatSurvey survey) {
        if (survey == null || survey.sessionId() == null) {
            return;
        }
        surveys.put(survey.sessionId(), survey);
    }

    @Override
    public Optional<CsatSurvey> find(String sessionId) {
        return Optional.ofNullable(surveys.get(sessionId));
    }

    @Override
    public List<CsatSurvey> findByWindow(String scopeId, long startMs, long endMs) {
        List<CsatSurvey> matched = new ArrayList<>();
        for (CsatSurvey survey : surveys.values()) {
            if (Objects.equals(survey.scopeId(), scopeId)
                && survey.invitedAtMs() >= startMs && survey.invitedAtMs() < endMs) {
                matched.add(survey);
            }
        }
        return List.copyOf(matched);
    }
}
