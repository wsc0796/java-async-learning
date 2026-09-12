package com.cms.flow.dag;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 启动期 DAG 装配器：扫描所有 {@link FlowDag} Bean，校验后写入 {@link DagDefinitionRegistry}。
 * 实现 {@link SmartInitializingSingleton}，保证在全部单例就绪后再装配（SPI Bean 已可注入）。
 */
@Component
public class FlowDagDefinitionCollector implements SmartInitializingSingleton {

    private final ApplicationContext applicationContext;
    private final DagDefinitionRegistry registry;

    public FlowDagDefinitionCollector(ApplicationContext applicationContext, DagDefinitionRegistry registry) {
        this.applicationContext = applicationContext;
        this.registry = registry;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(FlowDag.class);
        for (Object bean : beans.values()) {
            if (!(bean instanceof FlowDagDefinition definition)) {
                throw new IllegalStateException("@FlowDag bean must implement FlowDagDefinition: " + bean.getClass());
            }
            FlowDag meta = bean.getClass().getAnnotation(FlowDag.class);
            DagTopology.Builder topology = new DagTopology.Builder();
            // 业务侧声明节点与边
            definition.configure(topology);
            // 校验 output / 边引用 / 无环
            validate(meta, topology);
            registry.register(new DagDefinition(
                    meta.dagKey(),
                    meta.outputNodeKey(),
                    topology.nodes(),
                    topology.edges(),
                    topology.preValidators(),
                    topology.requestParamConverter(),
                    topology.fallbackProviderClass(),
                    topology.cacheKeyGeneratorClass()));
        }
    }

    /** 校验输出节点存在、边端点合法、图无环。 */
    private void validate(FlowDag meta, DagTopology.Builder topology) {
        Map<String, DagNodeSpec> nodes = topology.nodes();
        if (!nodes.containsKey(meta.outputNodeKey())) {
            throw new IllegalStateException("outputNodeKey not found in dag " + meta.dagKey() + ": " + meta.outputNodeKey());
        }
        Set<String> nodeIds = nodes.keySet();
        for (EdgeSpec edge : topology.edges()) {
            if (!nodeIds.contains(edge.fromNodeId()) || !nodeIds.contains(edge.toNodeId())) {
                throw new IllegalStateException("edge references unknown node in dag " + meta.dagKey() + ": " + edge);
            }
        }
        detectCycle(meta.dagKey(), nodeIds, topology.edges());
    }

    /** Kahn 拓扑排序：若访问节点数 < 总节点数，则存在环。 */
    private void detectCycle(String dagKey, Set<String> nodeIds, java.util.List<EdgeSpec> edges) {
        Map<String, Integer> indegree = new java.util.HashMap<>();
        Map<String, java.util.List<String>> adj = new java.util.HashMap<>();
        for (String id : nodeIds) {
            indegree.put(id, 0);
            adj.put(id, new java.util.ArrayList<>());
        }
        for (EdgeSpec edge : edges) {
            adj.get(edge.fromNodeId()).add(edge.toNodeId());
            indegree.merge(edge.toNodeId(), 1, Integer::sum);
        }
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        indegree.forEach((id, d) -> {
            if (d == 0) {
                queue.add(id);
            }
        });
        int visited = 0;
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            visited++;
            for (String next : adj.get(cur)) {
                int d = indegree.merge(next, -1, Integer::sum);
                if (d == 0) {
                    queue.add(next);
                }
            }
        }
        if (visited != nodeIds.size()) {
            Set<String> remaining = new HashSet<>(nodeIds);
            int finalVisited = visited;
            remaining.removeIf(id -> indegree.get(id) == 0 && finalVisited > 0);
            throw new IllegalStateException("cycle detected in dag " + dagKey);
        }
    }
}
