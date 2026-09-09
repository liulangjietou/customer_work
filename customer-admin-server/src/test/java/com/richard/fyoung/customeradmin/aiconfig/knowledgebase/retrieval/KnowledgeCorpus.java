package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.retrieval;

import java.util.List;

/**
 * 检索评估语料：一份仿真的客服制度文档 + 固定问题集。
 *
 * <p>刻意包含三种真实形态：<b>超长条款段落</b>（远超分片上限，必然被切开——
 * 无重叠切分的伤害就发生在这里）、<b>FAQ 短问答</b>（段落短、数量多，考验合并策略）、
 * <b>表述与提问用词不同</b>的条目（用户不会照着制度原话问）。</p>
 *
 * <p>问题集与答案片段一经确定就不要随手改：基线的价值全在于「同一把尺子量前后」。
 * 确需扩充时新增用例，不要修改既有用例的答案片段。</p>
 *
 * @author owlzhangfq@gmail.com
 */
final class KnowledgeCorpus {

    private KnowledgeCorpus() {
    }

    /** 答案片段：单独抽出来做常量，既保证与正文逐字一致，也便于查阅每条用例在考什么。 */
    static final String A_REFUND_WINDOW =
        "自签收次日起七日内可申请无理由退货，商品须保持完好、配件齐全、包装未影响二次销售。";
    static final String A_REFUND_TIMING =
        "退款将在仓库确认收货后的三个工作日内原路退回，银行到账时间以发卡行为准，最长不超过十五个工作日。";
    static final String A_FREIGHT_RULE =
        "无理由退货的往返运费由买家承担；因商品质量问题、错发漏发或描述不符引起的退货，往返运费由平台承担。";
    static final String A_EXCLUDE_ITEMS =
        "定制类商品、拆封后影响人身健康的个人护理用品、已激活或授权的数字商品，不适用无理由退货。";
    static final String A_WARRANTY =
        "整机保修期为自签收次日起十二个月，电池与充电器等易损配件保修期为六个月。";
    static final String A_REPAIR_TURNAROUND =
        "寄修件到达维修中心后，一般在五个工作日内完成检测与维修并寄回。";
    static final String A_INVOICE =
        "电子发票在订单完成后的次日开具，可在订单详情页自助下载；如需更换抬头，请在开具后三十日内联系客服。";
    static final String A_DELIVERY_REMOTE =
        "偏远地区（新疆、西藏、内蒙古部分区县）配送时效为五至七个工作日，其余地区为二至三个工作日。";
    static final String A_PRICE_PROTECT =
        "订单签收后十五日内，若同一商品在本店发生降价，可申请一次价格保护，差价以优惠券形式返还。";
    static final String A_ACCOUNT_LOCK =
        "连续五次输错密码将锁定账号三十分钟，锁定期间可通过找回密码流程重置后立即恢复登录。";

    /**
     * 语料正文。
     *
     * <p>第一段刻意写成一整段不换行的长条款（约 400 字，超过默认 1500 字符上限？不——
     * 它由多个相邻条款连写而成，正是现实中「一条制度写成一大段」的样子），
     * 其中同时包含退货窗口、退款时效、运费归属与除外情形四个知识点。
     * 无重叠切分会在这一段内部切开，把某些知识点劈成两半。</p>
     */
    static String content() {
        return String.join("\n\n",
            "《售后服务与退换货管理制度》",

            // 一整段连写的长条款：四个知识点挤在一段里，是切分策略最吃劲的地方
            "第一章 退换货。" + A_REFUND_WINDOW
                + "申请入口位于订单详情页的申请售后按钮，提交后系统会生成售后单号并短信通知。"
                + A_REFUND_TIMING
                + "若订单使用了优惠券，优惠券在退款时按未使用状态退回账户，已过期的不予补发。"
                + A_FREIGHT_RULE
                + "买家自行承担运费时可选择平台合作物流，费用在退款金额中直接抵扣。"
                + A_EXCLUDE_ITEMS
                + "上述除外情形在商品详情页均有显著标识，下单即视为知悉。",

            "第二章 保修。" + A_WARRANTY
                + "保修期内因产品自身质量问题产生的维修费用与往返运费均由平台承担。"
                + A_REPAIR_TURNAROUND
                + "如需更换主板等核心部件，检测周期可能延长至十个工作日，客服会主动同步进度。",

            "第三章 发票。" + A_INVOICE,

            "第四章 配送。" + A_DELIVERY_REMOTE
                + "大件家电由厂家直送并提供上门安装，安装时效由当地服务商与您电话约定。",

            "第五章 价格保护。" + A_PRICE_PROTECT
                + "参与秒杀、拼团等限时活动的商品不参与价格保护。",

            "第六章 账号安全。" + A_ACCOUNT_LOCK
                + "如怀疑账号被盗用，请立即联系客服冻结账号并修改绑定手机。");
    }

    /**
     * 问题集：用户的真实问法，与制度原话用词刻意不同。
     *
     * <p>例如用户会问「几天能退」而不是「无理由退货期限」，问「钱什么时候到账」
     * 而不是「退款时效」——检索要跨过这层措辞差异。</p>
     */
    static List<RetrievalQualityHarness.Case> cases() {
        return List.of(
            new RetrievalQualityHarness.Case("买了东西不想要了，几天之内可以退货", A_REFUND_WINDOW),
            new RetrievalQualityHarness.Case("退款一般多久能到账，钱什么时候原路退回", A_REFUND_TIMING),
            new RetrievalQualityHarness.Case("退货的来回运费谁出，质量问题也要我承担吗", A_FREIGHT_RULE),
            new RetrievalQualityHarness.Case("哪些商品不支持七天无理由退货", A_EXCLUDE_ITEMS),
            new RetrievalQualityHarness.Case("这个机器保修多久，电池也保修吗", A_WARRANTY),
            new RetrievalQualityHarness.Case("寄去维修一般几天能修好寄回来", A_REPAIR_TURNAROUND),
            new RetrievalQualityHarness.Case("发票什么时候开，抬头写错了怎么改", A_INVOICE),
            new RetrievalQualityHarness.Case("我在新疆，快递大概几天能送到", A_DELIVERY_REMOTE),
            new RetrievalQualityHarness.Case("买完就降价了，差价能退给我吗", A_PRICE_PROTECT),
            new RetrievalQualityHarness.Case("密码输错太多次账号被锁了怎么办", A_ACCOUNT_LOCK));
    }
}
