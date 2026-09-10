package com.richard.fyoung.customerwork.core.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 长期记忆召回质量：一把能分辨好坏的尺子。
 *
 * <h3>为什么需要它</h3>
 * <p>召回打分改了到底是变好还是变坏，光看代码说不清。报告 P1-16 指出召回靠字符重合度，
 * 但"有多差"从来没被量过——而这决定了该小改算法还是该上向量检索（后者要加列、要迁移、
 * 每条事实与每次查询都要调 embedding）。</p>
 *
 * <h3>用例分两组，这一点是关键</h3>
 * <p>混在一起算总召回率<b>会得出错误结论</b>：改成 bigram 打分后总召回率从 0.80 掉到 0.60，
 * 看起来是退步；但掉的那几条全是「忌口 vs 过敏」这类<b>没有共同实词</b>的同义改写——
 * 旧算法在它们上"召回成功"靠的是虚词碰巧命中，而同一个策略会把
 * 「我的那个是不是耳机的」的正确答案挤出前三（见 {@link #functionWordsMustNotDominate}）。</p>
 *
 * <p>所以分成「字面相关」与「同义改写」两组：前者是打分策略的责任，必须全中；
 * 后者字符层面本就无解，是向量召回的验收标准。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class MemoryRecallQualityTest {

    private static final int TOP_K = 3;

    /** 一个用户积累下来的事实。刻意混入若干与查询无关但共享常用字的条目。 */
    private static final List<String> FACTS = List.of(
        "用户的收货地址是北京市朝阳区望京街道 12 号",
        "用户偏好顺丰快递，不接受平邮",
        "用户是白银会员，享受满 199 免运费",
        "用户对海鲜过敏，不要推荐海产类商品",
        "用户的常用支付方式是微信支付",
        "用户投诉过一次物流延迟，情绪比较敏感",
        "用户希望客服在工作日的下午联系他",
        "用户购买过一台无线降噪耳机，型号 X200",
        "用户表示不需要发票，之前问过一次",
        "用户的手机尾号是 8823");

    private record Case(String query, String expected) {
    }

    /**
     * <b>字面相关</b>组：查询与事实共享实词，字符层面就能召回。
     *
     * <p>这一组是打分策略的责任范围，必须全部召回，没有借口。</p>
     */
    private static final List<Case> LITERAL_CASES = List.of(
        new Case("我上次说的地址还在用吗", FACTS.get(0)),
        new Case("帮我用顺丰发货", FACTS.get(1)),
        new Case("我的会员等级是什么", FACTS.get(2)),
        new Case("我买的那个耳机是什么型号", FACTS.get(7)),
        new Case("这单要开发票吗", FACTS.get(8)),
        new Case("我手机号后四位是多少", FACTS.get(9)));

    /**
     * <b>同义改写</b>组：用户换了说法，与事实<b>没有共同实词</b>。
     *
     * <p>「忌口」vs「过敏」、「付款」vs「支付方式」、「打电话」vs「联系」——
     * 字符层面无解，无论怎么改打分算法都召不回。这一组是向量召回的验收标准，
     * 不是当前打分策略的责任。</p>
     */
    private static final List<Case> SEMANTIC_CASES = List.of(
        new Case("有什么忌口需要注意的吗", FACTS.get(3)),
        new Case("我平时用什么付款的", FACTS.get(4)),
        new Case("之前那个快递问题解决了吗", FACTS.get(5)),
        new Case("什么时候方便给我打电话", FACTS.get(6)));

    @Test
    @DisplayName("打印两组召回质量")
    void printRecall() {
        System.out.println("[记忆召回·字面] " + evaluate(LITERAL_CASES));
        System.out.println("[记忆召回·同义改写] " + evaluate(SEMANTIC_CASES));
    }

    /**
     * 字面相关的查询必须全部召回。
     *
     * <p>长期记忆召不回来，等于智能体每次都在和一个陌生人说话——
     * 用户会明确感到「我上次说过的它不记得」。</p>
     */
    @Test
    @DisplayName("字面相关的查询全部召回")
    void literalQueriesFullyRecalled() {
        Report report = evaluate(LITERAL_CASES);

        assertTrue(report.recall >= 1.0,
            "共享实词的查询没能全部召回，打分策略有问题：" + report);
    }

    /**
     * 同义改写当前召不回——这条<b>记录现状</b>，不是在描述待修缺陷。
     *
     * <p>真正的解法是向量召回：给 {@code cw_long_term_memory} 加向量列、
     * 每条事实入库时调一次 embedding。那要连迁移与 embedding 成本一起评估，是独立的一件事。</p>
     *
     * <p><b>如果哪天这条断言红了</b>，说明有人接上了语义召回——那时该把这一组并入
     * 字面组的要求，而不是把断言改回去。</p>
     */
    @Test
    @DisplayName("同义改写当前召不回（现状记录，待向量召回补齐）")
    void semanticParaphraseNotYetRecalled() {
        Report report = evaluate(SEMANTIC_CASES);

        assertTrue(report.recall < 1.0,
            "同义改写全召回了——若确实接上了语义召回，请把这一组并入字面组的要求，"
                + "而不是留着这条已经恒真的断言：" + report);
    }

    /**
     * 虚词不该主导排序。
     *
     * <p>这是字符重合度打分最典型的失效：「的」「是」「我」几乎出现在每条事实里，
     * 查询里虚词越多，越多无关事实拿到分数。改动前实测：
     * 「我的那个是不是耳机的」召回的前三是手机尾号、收货地址、支付方式——唯独没有耳机那条。</p>
     */
    @Test
    @DisplayName("虚词不应主导排序")
    void functionWordsMustNotDominate() {
        List<String> hits = FactRelevanceScorer.topMatches(FACTS, "我的那个是不是耳机的", TOP_K);

        assertTrue(!hits.isEmpty() && hits.get(0).contains("耳机"),
            "排在第一的不是耳机那条，说明虚词主导了排序：" + hits);
    }

    /** 整句都是虚词时返回空，而不是硬凑几条无关记忆塞进提示词。 */
    @Test
    @DisplayName("整句虚词的查询不召回任何东西")
    void allStopWordQueryRecallsNothing() {
        assertTrue(FactRelevanceScorer.topMatches(FACTS, "是吗", TOP_K).isEmpty(),
            "把无关记忆塞进提示词比不召回更糟——模型会基于错误前提回答");
        assertTrue(FactRelevanceScorer.topMatches(FACTS, "这样啊", TOP_K).isEmpty());
    }

    private Report evaluate(List<Case> cases) {
        int recalled = 0;
        int top1 = 0;
        List<String> misses = new ArrayList<>();
        for (Case c : cases) {
            List<String> hits = FactRelevanceScorer.topMatches(FACTS, c.query(), TOP_K);
            if (hits.contains(c.expected())) {
                recalled++;
                if (hits.get(0).equals(c.expected())) {
                    top1++;
                }
            } else {
                misses.add(c.query());
            }
        }
        int total = cases.size();
        return new Report((double) recalled / total, (double) top1 / total, total, misses);
    }

    private record Report(double recall, double top1, int total, List<String> misses) {

        @Override
        public String toString() {
            return String.format("Recall@%d=%.2f Top1=%.2f (共 %d 条)%s",
                TOP_K, recall, top1, total, misses.isEmpty() ? "" : " 未召回：" + misses);
        }
    }
}
