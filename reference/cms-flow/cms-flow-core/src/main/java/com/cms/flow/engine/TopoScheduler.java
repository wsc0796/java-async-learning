package com.cms.flow.engine;

import com.cms.flow.dag.DagDefinition;
import com.cms.flow.dag.DagNodeSpec;
import com.cms.flow.dag.EdgeSpec;
import com.cms.flow.executor.FlowExecutorRegistry;
import com.cms.flow.executor.NodeInvokeExecutorResolver;
import com.cms.flow.nodetype.AggregationNodeExecutor;
import com.cms.flow.nodetype.HttpNodeExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * 分层拓扑调度器：Kahn 波次在 dagScheduler 上编排；
 * 同波节点提交到按 {@code InvokeExecutorType} 解析的 invoke 池并行执行，波次之间 join。
 */
@Component
public class TopoScheduler {

    private static final Logger log = LoggerFactory.getLogger(TopoScheduler.class);

    private final HttpNodeExecutor httpNodeExecutor;
    private final AggregationNodeExecutor aggregationNodeExecutor;
    private final FlowExecutorRegistry executorRegistry;
    private final NodeInvokeExecutorResolver invokeResolver;

    public TopoScheduler(HttpNodeExecutor httpNodeExecutor,
                         AggregationNodeExecutor aggregationNodeExecutor,
                         FlowExecutorRegistry executorRegistry,
                         NodeInvokeExecutorResolver invokeResolver) {
        this.httpNodeExecutor = httpNodeExecutor;
        this.aggregationNodeExecutor = aggregationNodeExecutor;
        this.executorRegistry = executorRegistry;
        this.invokeResolver = invokeResolver;
    }

    /**
     * 执行整张 DAG，直到所有节点完成或发现环。
     * HTTP 线程在此阻塞等待；实际 Kahn 循环跑在 dagScheduler。
     */
    public void execute(DagDefinition dag, ExecutionContext ctx) {
        Executor dagScheduler = executorRegistry.dagScheduler();
        try {
            CompletableFuture.runAsync(() -> runWaves(dag, ctx), dagScheduler).join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("dag execution failed: " + dag.dagKey(), cause);
        }
    }

    private void runWaves(DagDefinition dag, ExecutionContext ctx) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> successors = new HashMap<>();
        for (String nodeId : dag.nodes().keySet()) {
            indegree.put(nodeId, 0);
            successors.put(nodeId, new ArrayList<>());
        }
        for (EdgeSpec edge : dag.edges()) {
            successors.get(edge.fromNodeId()).add(edge.toNodeId());
            indegree.merge(edge.toNodeId(), 1, Integer::sum);
        }

        List<String> ready = new ArrayList<>();
        indegree.forEach((id, d) -> {
            if (d == 0) {
                ready.add(id);
            }
        });

        int finished = 0;
        int total = dag.nodes().size();
        List<String> current = ready;
        while (!current.isEmpty()) {
            List<CompletableFuture<Void>> wave = new ArrayList<>(current.size());
            for (String nodeId : current) {
                DagNodeSpec spec = dag.nodes().get(nodeId);
                Executor invoke = invokeResolver.resolve(spec);
                wave.add(CompletableFuture.runAsync(() -> runNode(dag, ctx, nodeId), invoke));
            }
            CompletableFuture.allOf(wave.toArray(CompletableFuture[]::new)).join();
            finished += current.size();

            List<String> nextReady = new ArrayList<>();
            for (String nodeId : current) {
                for (String next : successors.get(nodeId)) {
                    int left = indegree.merge(next, -1, Integer::sum);
                    if (left == 0) {
                        nextReady.add(next);
                    }
                }
            }
            current = nextReady;
        }

        if (finished != total) {
            throw new IllegalStateException("dag not fully executed, possible cycle: " + dag.dagKey());
        }
    }

    /**
     * 按节点类型分发到对应执行器。
     * 扩展 Dubbo 时：注入 DubboNodeExecutor，并在此增加 case。
     */
    private void runNode(DagDefinition dag, ExecutionContext ctx, String nodeId) {
        DagNodeSpec spec = dag.nodes().get(nodeId);
        log.debug("execute node={} type={} invokeType={} dag={}",
                nodeId, spec.type(), spec.invokeExecutorType(), dag.dagKey());
        switch (spec.type()) {
            case HTTP -> httpNodeExecutor.execute(spec, ctx);
            case AGGREGATION -> aggregationNodeExecutor.execute(spec, ctx);
        }
    }
}
