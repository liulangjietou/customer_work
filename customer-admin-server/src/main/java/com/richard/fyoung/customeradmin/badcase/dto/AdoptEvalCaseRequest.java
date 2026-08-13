package com.richard.fyoung.customeradmin.badcase.dto;

import com.richard.fyoung.customerwork.capability.eval.EvalType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 把 badcase 采纳为评测用例的请求。
 *
 * <p>用户输入直接取自 badcase（那才是真实翻过车的输入，不该改写），
 * 只有"期望什么"需要人来填——这正是当初答错的地方。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
public class AdoptEvalCaseRequest {

    /** 用例编号，同类型内唯一；与种子用例同号会覆盖种子，故接口层会先查重再写。 */
    @NotBlank(message = "用例编号不能为空")
    private String caseId;

    /** 归入哪类评测。 */
    @NotNull(message = "评测类型不能为空")
    private EvalType evalType;

    /**
     * 期望值：INTENT 传期望意图（留空表示期望规则快车道<b>不</b>命中、应交 LLM）；
     * QUALITY 传期望回复要点。
     */
    private String expected;

    /** 归类标签，便于按类目看短板。 */
    private String category;
}
