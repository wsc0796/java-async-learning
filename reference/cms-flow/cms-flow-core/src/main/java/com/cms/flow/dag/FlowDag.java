package com.cms.flow.dag;

/**
 * 标记一个 Spring Bean 为「代码声明式 DAG」。

 * 被标注的类必须实现 {@link FlowDagDefinition}；
 * 启动时由 {@link FlowDagDefinitionCollector} 扫描并注册。
 */
@java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
@org.springframework.stereotype.Component
public @interface FlowDag {

    /** 图唯一键，对应接口路径中的 {dagKey}。 */
    String dagKey();

    /** 输出节点 id，其结果作为聚合接口返回值。 */
    String outputNodeKey();
}
