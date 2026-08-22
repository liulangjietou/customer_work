package com.richard.fyoung.customeradmin.aiconfig.channel.publish;

/** 发布任务租约已被其它 worker 接管；旧 worker 必须立即停止外写。 */
public class RuntimePublishLeaseLostException extends IllegalStateException {

    public RuntimePublishLeaseLostException(String taskId) {
        super("runtime publish lease lost: " + taskId);
    }
}
