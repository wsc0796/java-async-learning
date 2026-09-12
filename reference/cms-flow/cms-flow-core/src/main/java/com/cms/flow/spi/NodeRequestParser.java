package com.cms.flow.spi;

import com.cms.flow.engine.ExecutionContext;

/**
 * 叶子节点请求组装器：从 bizParams 或上游节点结果，拼出下游调用参数。
 * 返回下游 DTO；HTTP 发往下游时会包成单元素 List（兼容 batch stub）。
 * 返回 null 表示无法组装（例如上游缺失），执行器将软失败而不调用下游。
 */
@FunctionalInterface
public interface NodeRequestParser {

    /**
     * @return 下游业务请求对象；null 表示跳过下游调用并软失败
     */
    Object build(String nodeId, ExecutionContext ctx);
}
