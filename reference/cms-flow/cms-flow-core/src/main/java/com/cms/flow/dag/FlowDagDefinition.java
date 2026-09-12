package com.cms.flow.dag;

/**
 * 代码 DAG 定义契约：在 {@link #configure} 中声明节点与边。
 */
public interface FlowDagDefinition {

    /**
     * 装配拓扑。
     *
     * @param topology 节点 / 边 Builder
     */
    void configure(DagTopology.Builder topology);
}
