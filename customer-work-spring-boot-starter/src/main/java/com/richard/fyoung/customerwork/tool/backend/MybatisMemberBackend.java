package com.richard.fyoung.customerwork.tool.backend;

import com.richard.fyoung.customerwork.tool.backend.entity.MemberAccountLogDO;
import com.richard.fyoung.customerwork.tool.backend.entity.MemberDO;
import com.richard.fyoung.customerwork.tool.backend.mapper.MemberAccountLogMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.MemberMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 会员/账户后端的 MyBatis-Plus 实现：积分/等级从 {@code cw_member} 表读取；账户问题处置沿用固定话术
 * 并将处理记录落库到 {@code cw_member_account_log}。
 *
 * <p>输出文案对齐 {@link MockMemberBackend}；查询走 {@link MemberMapper}（BaseMapper.selectById），日志落库走
 * {@link MemberAccountLogMapper}（BaseMapper.insert）。本类由 starter 的 {@code ToolBackendConfig} 在
 * {@code tool-backend.mode=jdbc} 时装配（种子数据集中在 {@code customer-work-schema.sql}）。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisMemberBackend implements MemberBackend {

    private static final Logger log = LoggerFactory.getLogger(MybatisMemberBackend.class);

    /** 积分抵扣比例：100 积分抵 1 元。 */
    private static final BigDecimal POINTS_PER_YUAN = new BigDecimal("100");

    /** 账户问题固定处置话术（与 Mock 一致）。 */
    private static final String ACCOUNT_ISSUE_GUIDE =
        "若无法登录，请尝试『验证码登录』或重置密码；若提示账号异常，可能触发风控，"
      + "请提供注册手机号由人工核验解封。";

    private final MemberMapper memberMapper;
    private final MemberAccountLogMapper memberAccountLogMapper;

    public MybatisMemberBackend(MemberMapper memberMapper, MemberAccountLogMapper memberAccountLogMapper) {
        this.memberMapper = memberMapper;
        this.memberAccountLogMapper = memberAccountLogMapper;
    }

    @Override
    public Mono<String> queryPoints(String userId) {
        return Mono.fromSupplier(() -> doQueryPoints(userId));
    }

    @Override
    public Mono<String> queryMemberLevel(String userId) {
        return Mono.fromSupplier(() -> doQueryMemberLevel(userId));
    }

    @Override
    public Mono<String> resolveAccountIssue(String issue) {
        return Mono.fromSupplier(() -> doResolveAccountIssue(issue));
    }

    private String doQueryPoints(String userId) {
        try {
            MemberDO member = memberMapper.selectById(userId);
            if (member == null) {
                return "未查询到会员 " + userId + " 的积分信息，请确认账号是否已注册会员。";
            }
            int points = member.getPoints();
            int expiring = member.getPointsExpiring();
            String deductible = new BigDecimal(points)
                .divide(POINTS_PER_YUAN, 2, RoundingMode.HALF_UP).toPlainString();
            String head = "会员 " + userId + " 当前积分 " + points + " 分，可抵扣 " + deductible + " 元；";
            return expiring > 0
                ? head + "本月底将有 " + expiring + " 分到期，建议尽快使用。"
                : head + "近期无积分到期。";
        } catch (Exception e) {
            log.error("member points query failed, code={}, userId={}", "MEMBER-BACKEND-POINTS-FAIL", userId, e);
            return "会员系统暂时不可用，建议稍后再试。";
        }
    }

    private String doQueryMemberLevel(String userId) {
        try {
            MemberDO member = memberMapper.selectById(userId);
            if (member == null) {
                return "未查询到会员 " + userId + " 的等级信息，请确认账号是否已注册会员。";
            }
            String gap = member.getUpgradeGap().toPlainString();
            return "会员 " + userId + " 等级：" + member.getLevel() + "；权益：" + member.getBenefits() + "；"
                + "再消费 " + gap + " 元可升级" + member.getNextLevel() + "。";
        } catch (Exception e) {
            log.error("member level query failed, code={}, userId={}", "MEMBER-BACKEND-LEVEL-FAIL", userId, e);
            return "会员系统暂时不可用，建议稍后再试。";
        }
    }

    /** 账户问题：固定话术 + 落一条处理日志（真实落库）。 */
    private String doResolveAccountIssue(String issue) {
        try {
            MemberAccountLogDO record = new MemberAccountLogDO();
            record.setIssue(issue);
            record.setHandling(ACCOUNT_ISSUE_GUIDE);
            record.setCreatedAtMs(System.currentTimeMillis());
            memberAccountLogMapper.insert(record);
        } catch (Exception e) {
            log.error("member account issue log failed, code={}, issue={}", "MEMBER-BACKEND-ACCOUNT-FAIL", issue, e);
        }
        return "关于「" + issue + "」：" + ACCOUNT_ISSUE_GUIDE;
    }
}
