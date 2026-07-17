package com.richard.fyoung.customerwork.quality;

import lombok.Getter;

import java.util.List;

/**
 * 会话质检报告：评分 + 是否通过 + 问题清单。
 * @author owlzhangfq@gmail.com
 */
@Getter
public class QualityReport {

    private final int score;
    private final boolean passed;
    private final List<String> issues;

    public QualityReport(int score, boolean passed, List<String> issues) {
        this.score = score;
        this.passed = passed;
        this.issues = issues;
    }
}
