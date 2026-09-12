package com.cms.flow.executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 副作用任务（写 LKG 等）：提交 sideEffect 池；拒绝则丢弃并计数，不反压主链路。
 */
public class SideEffectRunner {

    private static final Logger log = LoggerFactory.getLogger(SideEffectRunner.class);

    private final Executor sideEffectExecutor;
    private final FlowExecutorMetrics metrics;

    public SideEffectRunner(FlowExecutorRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        this.sideEffectExecutor = registry.sideEffect();
        this.metrics = registry.metrics();
    }

    public SideEffectRunner(Executor sideEffectExecutor, FlowExecutorMetrics metrics) {
        this.sideEffectExecutor = Objects.requireNonNull(sideEffectExecutor, "sideEffectExecutor");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        try {
            metrics.incrementSideEffectSubmitted();
            sideEffectExecutor.execute(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    log.warn("side-effect task failed: {}", t.getMessage());
                }
            });
        } catch (RejectedExecutionException ex) {
            metrics.incrementSideEffectDropped();
            log.warn("side-effect rejected (dropped): {}", ex.getMessage());
        }
    }
}
