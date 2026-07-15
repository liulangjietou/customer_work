package com.richard.fyoung.customerwork.tool.backend;

import com.richard.fyoung.customerwork.tool.backend.entity.ComplaintDO;
import com.richard.fyoung.customerwork.tool.backend.mapper.ComplaintMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 投诉工单后端的 MyBatis-Plus 实现：投诉工单真实落库到 {@code cw_complaint}。
 *
 * <p>输出文案对齐 {@link MockComplaintBackend}；建单走 {@link ComplaintMapper}（BaseMapper.insert），查询走
 * BaseMapper.selectById。本类由 starter 的 {@code ToolBackendConfig} 在 {@code tool-backend.mode=jdbc} 时装配
 * （种子数据集中在 {@code customer-work-schema.sql}）。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisComplaintBackend implements ComplaintBackend {

    private static final Logger log = LoggerFactory.getLogger(MybatisComplaintBackend.class);

    private static final String ID_PREFIX = "CP";

    /** 工单状态：处理中 / 已回复。 */
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_RESOLVED = "RESOLVED";

    private final ComplaintMapper complaintMapper;

    public MybatisComplaintBackend(ComplaintMapper complaintMapper) {
        this.complaintMapper = complaintMapper;
    }

    @Override
    public Mono<String> fileComplaint(String orderId, String content) {
        return Mono.fromSupplier(() -> doFileComplaint(orderId, content));
    }

    @Override
    public Mono<String> queryComplaint(String ticketId) {
        return Mono.fromSupplier(() -> doQueryComplaint(ticketId));
    }

    private String doFileComplaint(String orderId, String content) {
        String complaintNo = ID_PREFIX + System.currentTimeMillis();
        try {
            ComplaintDO record = new ComplaintDO();
            record.setComplaintNo(complaintNo);
            record.setOrderId(orderId);
            record.setContent(content);
            record.setStatus(STATUS_PROCESSING);
            record.setCreatedAtMs(System.currentTimeMillis());
            complaintMapper.insert(record);
            log.info("file complaint: ticket={}, order={}", complaintNo, orderId);
            return "已为您创建投诉工单 " + complaintNo + "（订单 " + orderId + "），"
                + "我们将在 24 小时内跟进处理，结果将短信通知您。";
        } catch (Exception e) {
            log.error("file complaint failed, code={}, orderId={}", "COMPLAINT-BACKEND-FILE-FAIL", orderId, e);
            return "投诉工单系统暂时不可用，已为您转接人工坐席。";
        }
    }

    private String doQueryComplaint(String ticketId) {
        try {
            ComplaintDO complaint = complaintMapper.selectById(ticketId);
            if (complaint == null) {
                return "未查询到投诉工单 " + ticketId + "，请核对工单号。";
            }
            if (STATUS_RESOLVED.equals(complaint.getStatus())) {
                return "投诉工单 " + ticketId + " 当前状态：已处理完毕，感谢您的反馈。";
            }
            return "投诉工单 " + ticketId + " 当前状态：处理中，已分派至对应部门，预计 1 个工作日内回复。";
        } catch (Exception e) {
            log.error("query complaint failed, code={}, ticketId={}", "COMPLAINT-BACKEND-QUERY-FAIL", ticketId, e);
            return "投诉工单查询暂时不可用，建议稍后再试。";
        }
    }
}
