package com.cms.flow.engine;

import com.cms.flow.api.FlowResult;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单次聚合调用的共享执行状态。
 * 核心职责：
 *   保存 bizParams（可为原始 Map，或经 Converter 后的强类型）
 *   按 nodeId 存放各节点产出：{@code FlowResult}
 *   下游节点通过 {@link #getNodeResult} 读取上游结果
 * 节点间并行写不同 nodeId，因此 nodeResults 使用 ConcurrentHashMap。
 */
public final class ExecutionContext {

    private final String dagKey;
    private final Object bizParams;
    /** nodeId → 该节点对本请求的结果 */
    private final Map<String, FlowResult<Object>> nodeResults = new ConcurrentHashMap<>();

    public ExecutionContext(String dagKey, Object bizParams) {
        this.dagKey = dagKey;
        this.bizParams = bizParams;
    }

    public String dagKey() {
        return dagKey;
    }

    /**
     * 取业务入参（Map 形态）。
     * 若已转换为强类型，返回 empty Map——请改用 {@link #getBizParamsAs(Class)}。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getBizParams() {
        if (bizParams instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    /**
     * 取强类型业务入参；类型不匹配或不存在时返回 null。
     */
    public <T> T getBizParamsAs(Class<T> type) {
        if (type.isInstance(bizParams)) {
            return type.cast(bizParams);
        }
        return null;
    }

    /** 节点执行完毕后写入本节点结果。 */
    public void putNodeResult(String nodeId, FlowResult<Object> result) {
        nodeResults.put(nodeId, result);
    }

    /** 取某节点对本请求的结果；不存在则返回 null。 */
    public FlowResult<Object> getNodeResult(String nodeId) {
        return nodeResults.get(nodeId);
    }
}
