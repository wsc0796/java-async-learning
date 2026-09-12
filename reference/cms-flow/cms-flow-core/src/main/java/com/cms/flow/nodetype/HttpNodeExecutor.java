package com.cms.flow.nodetype;

import com.cms.flow.api.FlowResult;
import com.cms.flow.autoconfigure.CmsFlowFallbackProperties;
import com.cms.flow.autoconfigure.CmsFlowHttpProperties;
import com.cms.flow.cache.FallbackCacheLevel;
import com.cms.flow.cache.FallbackCacheScope;
import com.cms.flow.cache.FallbackCacheStore;
import com.cms.flow.cache.FallbackTtl;
import com.cms.flow.cache.response.ResponseCacheCoordinator;
import com.cms.flow.cache.response.ResponseCacheSupport;
import com.cms.flow.dag.DagNodeSpec;
import com.cms.flow.engine.ExecutionContext;
import com.cms.flow.executor.SideEffectRunner;
import com.cms.flow.resilience.NodeResilienceGuard;
import com.cms.flow.spi.NodeCacheKeyGenerator;
import com.cms.flow.spi.NodeFallbackProvider;
import com.cms.flow.spi.NodeRequestParser;
import com.cms.flow.spi.NodeResponseParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

/**
 * HTTP 节点：响应缓存读前短路 → 熔断保护调用 → 异步写响应缓存 + LKG；
 * 失败时按配置读节点兜底，未启用则软失败留给整图 Fallback。
 */
@Component
public class HttpNodeExecutor implements NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(HttpNodeExecutor.class);

    private static final ParameterizedTypeReference<List<Object>> LIST_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final ApplicationContext applicationContext;
    private final RestClient restClient;
    private final CmsFlowHttpProperties httpProperties;
    private final CmsFlowFallbackProperties fallbackProperties;
    private final NodeResilienceGuard resilienceGuard;
    private final FallbackCacheStore fallbackCacheStore;
    private final SideEffectRunner sideEffectRunner;
    private final ResponseCacheCoordinator responseCacheCoordinator;

    public HttpNodeExecutor(ApplicationContext applicationContext,
                            RestClient.Builder restClientBuilder,
                            CmsFlowHttpProperties httpProperties,
                            CmsFlowFallbackProperties fallbackProperties,
                            NodeResilienceGuard resilienceGuard,
                            FallbackCacheStore fallbackCacheStore,
                            SideEffectRunner sideEffectRunner,
                            ResponseCacheCoordinator responseCacheCoordinator) {
        this.applicationContext = applicationContext;
        this.httpProperties = httpProperties;
        this.fallbackProperties = fallbackProperties;
        this.resilienceGuard = resilienceGuard;
        this.fallbackCacheStore = fallbackCacheStore;
        this.sideEffectRunner = sideEffectRunner;
        this.responseCacheCoordinator = responseCacheCoordinator;
        this.restClient = restClientBuilder.build();
    }

    @Override
    public void execute(DagNodeSpec spec, ExecutionContext ctx) {
        NodeFallbackProvider fallbackProvider = resolveFallbackProvider(spec);
        NodeCacheKeyGenerator cacheKeyGenerator = resolveCacheKeyGenerator(spec);

        Optional<ResponseCacheCoordinator.ReadResult> cached =
                responseCacheCoordinator.tryReadNode(ctx.dagKey(), spec.id(), ctx, cacheKeyGenerator);
        if (cached.isPresent()) {
            ResponseCacheCoordinator.ReadResult hit = cached.get();
            ctx.putNodeResult(spec.id(), hit.result());
            if (hit.anyStale()) {
                // 使用完整响应缓存 key 做单飞，避免同节点不同 bizKey 互相抢刷新资格
                responseCacheCoordinator.scheduleNodeRefresh(
                        ctx.dagKey(), spec.id(), hit.cacheKey(), () -> {
                    FlowResult<Object> refreshed = resilienceGuard.execute(spec.id(),
                            () -> invokeHttp(spec, ctx));
                    responseCacheCoordinator.writeNodeAsync(
                            ctx.dagKey(), spec.id(), ctx, refreshed, cacheKeyGenerator);
                });
            }
            return;
        }

        try {
            FlowResult<Object> parsed = resilienceGuard.execute(spec.id(),
                    () -> invokeHttp(spec, ctx));
            responseCacheCoordinator.writeNodeAsync(
                    ctx.dagKey(), spec.id(), ctx, parsed, cacheKeyGenerator);
            writeNodeFallbackCacheAsync(spec, ctx, parsed, fallbackProvider);
            ctx.putNodeResult(spec.id(), parsed);
        } catch (Throwable ex) {
            log.warn("http node failed: dagKey={}, nodeId={}, reason={}",
                    ctx.dagKey(), spec.id(), ex.getMessage());
            ctx.putNodeResult(spec.id(), softFailOrNodeFallback(fallbackProvider, spec, ctx, ex));
        }
    }

    private FlowResult<Object> invokeHttp(DagNodeSpec spec, ExecutionContext ctx) {
        NodeRequestParser requestParser = applicationContext.getBean(spec.requestParserClass());
        NodeResponseParser responseParser = applicationContext.getBean(spec.responseParserClass());
        Object requestDto = requestParser.build(spec.id(), ctx);
        if (requestDto == null) {
            return FlowResult.fail("E0502", "request build skipped");
        }
        // 下游仍为 batch List API，单请求包成单元素列表
        List<Object> wireBody = List.of(requestDto);
        List<Object> rawList = restClient.post()
                .uri(httpProperties.requireUrl(spec.httpUrlKey()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(wireBody)
                .retrieve()
                .body(LIST_TYPE);
        return responseParser.parse(spec.id(), ctx, rawList);
    }

    private void writeNodeFallbackCacheAsync(DagNodeSpec spec,
                                             ExecutionContext ctx,
                                             FlowResult<Object> parsed,
                                             NodeFallbackProvider fallbackProvider) {
        if (!fallbackProperties.isEnabled() || fallbackProvider == null || parsed == null) {
            return;
        }
        Optional<FallbackCacheLevel> levelOpt =
                fallbackProperties.resolveNodeFallbackLevel(ctx.dagKey(), spec.id());
        Optional<FallbackTtl> ttlOpt = fallbackProperties.resolveNodeTtl(ctx.dagKey(), spec.id());
        if (levelOpt.isEmpty() || ttlOpt.isEmpty()) {
            return;
        }
        if (!ResponseCacheSupport.shouldWrite(fallbackProperties.effectiveWriteSampleRate())) {
            return;
        }
        if (!parsed.isSuccess() || parsed.data() == null) {
            return;
        }
        String key = fallbackProvider.resolveCacheKey(spec.id(), ctx, parsed.data());
        if (key == null || key.isBlank()) {
            return;
        }
        FallbackCacheLevel level = levelOpt.get();
        FallbackTtl ttl = ttlOpt.get();
        Object data = parsed.data();
        String nodeId = spec.id();
        sideEffectRunner.execute(() -> {
            fallbackCacheStore.put(key, data, ttl.l1Duration(), ttl.l2Duration(),
                    level, FallbackCacheScope.NODE);
            log.debug("node LKG written async: nodeId={}", nodeId);
        });
    }

    private FlowResult<Object> softFailOrNodeFallback(NodeFallbackProvider fallbackProvider,
                                                      DagNodeSpec spec,
                                                      ExecutionContext ctx,
                                                      Throwable cause) {
        if (fallbackProperties.isEnabled()
                && fallbackProvider != null
                && fallbackProperties.isNodeFallbackEnabled(ctx.dagKey(), spec.id())) {
            try {
                FlowResult<Object> fb = fallbackProvider.fallback(spec.id(), ctx, cause);
                if (fb != null) {
                    return fb;
                }
            } catch (Throwable t) {
                log.warn("node fallback provider failed: dagKey={}, nodeId={}, reason={}",
                        ctx.dagKey(), spec.id(), t.getMessage());
            }
        }
        return failResult(cause);
    }

    private static FlowResult<Object> failResult(Throwable cause) {
        String message = cause == null ? "node execution failed"
                : (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
        return FlowResult.fail("E0502", message);
    }

    private NodeFallbackProvider resolveFallbackProvider(DagNodeSpec spec) {
        Class<? extends NodeFallbackProvider> type = spec.fallbackProviderClass();
        if (type == null) {
            return null;
        }
        return applicationContext.getBean(type);
    }

    private NodeCacheKeyGenerator resolveCacheKeyGenerator(DagNodeSpec spec) {
        Class<? extends NodeCacheKeyGenerator> type = spec.cacheKeyGeneratorClass();
        if (type == null) {
            return null;
        }
        return applicationContext.getBean(type);
    }
}
