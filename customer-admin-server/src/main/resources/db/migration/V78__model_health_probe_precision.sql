SET NAMES utf8mb4;

-- 健康快照 CAS 以 probe 实际开始时间排序；秒级 DATETIME 无法区分同秒并发探测。
ALTER TABLE `ai_model_health_snapshot`
    MODIFY COLUMN `last_probe_at` DATETIME(6) DEFAULT NULL COMMENT '最近探测实际开始时间（微秒）';

-- 事件保留相同精度，便于审计快照为什么接受或拒绝某次并发探测。
ALTER TABLE `ai_model_health_event`
    MODIFY COLUMN `occurred_at` DATETIME(6) NOT NULL COMMENT '探测实际开始时间（微秒）';
