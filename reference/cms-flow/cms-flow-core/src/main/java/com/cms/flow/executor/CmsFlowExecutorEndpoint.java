package com.cms.flow.executor;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Actuator 端点：GET /actuator/cmsFlowExecutors。
 */
@Component
@ConditionalOnClass(Endpoint.class)
@Endpoint(id = "cmsFlowExecutors")
public class CmsFlowExecutorEndpoint {

    private final FlowExecutorRegistry registry;
    private final FlowExecutorMetrics metrics;

    public CmsFlowExecutorEndpoint(FlowExecutorRegistry registry, FlowExecutorMetrics metrics) {
        this.registry = registry;
        this.metrics = metrics;
    }

    @ReadOperation
    public Map<String, Object> executors() {
        registry.snapshot();
        return metrics.asMap();
    }
}
