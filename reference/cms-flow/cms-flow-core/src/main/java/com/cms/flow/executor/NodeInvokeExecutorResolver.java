package com.cms.flow.executor;

import com.cms.flow.dag.DagNodeSpec;
import com.cms.flow.dag.NodeType;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * 按节点 {@link InvokeExecutorType} 解析调用池；AGGREGATION 始终走 NORMAL。
 */
public class NodeInvokeExecutorResolver {

    private final FlowExecutorRegistry registry;

    public NodeInvokeExecutorResolver(FlowExecutorRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public Executor resolve(DagNodeSpec spec) {
        Objects.requireNonNull(spec, "spec");
        if (spec.type() == NodeType.AGGREGATION) {
            return registry.nodeInvokeNormal();
        }
        InvokeExecutorType type = spec.invokeExecutorType();
        if (type == null) {
            type = InvokeExecutorType.NORMAL;
        }
        return switch (type) {
            case FAST -> registry.fastNodeInvoke();
            case SLOW -> registry.slowNodeInvoke();
            case NORMAL -> registry.nodeInvokeNormal();
        };
    }
}
