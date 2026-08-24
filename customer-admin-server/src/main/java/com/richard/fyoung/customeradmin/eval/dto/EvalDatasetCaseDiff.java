package com.richard.fyoung.customeradmin.eval.dto;

import com.fasterxml.jackson.databind.JsonNode;

/** 同一用例编号在两个不可变版本中的内容变化。 */
public record EvalDatasetCaseDiff(String caseId, JsonNode before, JsonNode after) {
}
