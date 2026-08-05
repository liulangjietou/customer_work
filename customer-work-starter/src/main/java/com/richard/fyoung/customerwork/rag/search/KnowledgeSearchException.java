package com.richard.fyoung.customerwork.rag.search;

/**
 * 外部 RAG 检索失败（单库 fast fail 语义）：地址被安全策略拦截以外的一切失败都收敛到本异常
 * ——网络不可达 / 超时 / 非 200 / 响应 {@code code != OK} / 响应结构非法。
 *
 * <p>message 已经过 {@link KnowledgeSearchOps#diagnose} 翻译成"能直接定位问题"的文案，
 * 宿主模块的调用壳直接转成自己的业务异常码即可，无需再加工。</p>
 * @author owlzhangfq@gmail.com
 */
public class KnowledgeSearchException extends RuntimeException {

    public KnowledgeSearchException(String message) {
        super(message);
    }

    public KnowledgeSearchException(String message, Throwable cause) {
        super(message, cause);
    }
}
