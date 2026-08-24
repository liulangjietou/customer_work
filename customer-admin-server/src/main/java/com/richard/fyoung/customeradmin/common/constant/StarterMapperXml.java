package com.richard.fyoung.customeradmin.common.constant;

/**
 * starter 侧 Mapper XML 在 classpath 上的位置。
 *
 * <p>后台经跨库门面复用客服端库的 Mapper 时要显式登记 XML 位置。此前每个门面工厂各写一遍
 * {@code classpath*:customerwork/mapper/} 前缀（17 处），其中两个 XML 还被两个门面各写一份——
 * starter 换资源目录时得挨个改，漏一个的表现是<b>门面启动正常、调到那条语句才报找不到</b>。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class StarterMapperXml {

    /** starter 的 Mapper XML 资源目录（与 {@code CustomerWorkPersistenceConfig} 约定一致）。 */
    private static final String PREFIX = "classpath*:customerwork/mapper/";

    public static final String AGENT_CALL_LOG = PREFIX + "AgentCallLogMapper.xml";
    public static final String AGENT_CALL_SEGMENT = PREFIX + "AgentCallSegmentMapper.xml";
    public static final String BADCASE = PREFIX + "BadcaseMapper.xml";
    public static final String CSAT_SURVEY = PREFIX + "CsatSurveyMapper.xml";
    public static final String DEAD_LETTER = PREFIX + "DeadLetterMapper.xml";
    public static final String EVAL_CASE = PREFIX + "EvalCaseMapper.xml";
    public static final String EVAL_DATASET_RELEASE = PREFIX + "EvalDatasetReleaseMapper.xml";
    public static final String EVAL_DATASET_SNAPSHOT = PREFIX + "EvalDatasetSnapshotMapper.xml";
    public static final String EVAL_RUN = PREFIX + "EvalRunMapper.xml";
    public static final String KNOWLEDGE = PREFIX + "KnowledgeMapper.xml";
    public static final String KNOWLEDGE_GAP = PREFIX + "KnowledgeGapMapper.xml";
    public static final String PROMPT_VERSION = PREFIX + "PromptVersionMapper.xml";
    public static final String RATE_LIMIT_RULE = PREFIX + "RateLimitRuleMapper.xml";
    public static final String SEMANTIC_CACHE = PREFIX + "SemanticCacheMapper.xml";
    public static final String SENSITIVE_WORD = PREFIX + "SensitiveWordMapper.xml";
    public static final String SUBJECT_QUOTA_HIT = PREFIX + "SubjectQuotaHitMapper.xml";
    public static final String SUBJECT_QUOTA_LEVEL = PREFIX + "SubjectQuotaLevelMapper.xml";

    private StarterMapperXml() {
    }
}
