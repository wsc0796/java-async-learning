package com.cms.flow.engine;

import com.cms.flow.api.AggregateCommand;
import com.cms.flow.api.FlowResult;
import com.cms.flow.api.FlowService;
import com.cms.flow.autoconfigure.CmsFlowFallbackProperties;
import com.cms.flow.cache.FallbackCacheLevel;
import com.cms.flow.cache.FallbackCacheScope;
import com.cms.flow.cache.FallbackCacheStore;
import com.cms.flow.cache.FallbackTtl;
import com.cms.flow.cache.response.ResponseCacheCoordinator;
import com.cms.flow.cache.response.ResponseCacheSupport;
import com.cms.flow.dag.DagDefinition;
import com.cms.flow.dag.DagDefinitionRegistry;
import com.cms.flow.executor.SideEffectRunner;
import com.cms.flow.spi.DagCacheKeyGenerator;
import com.cms.flow.spi.DagFallbackProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * {@link FlowService} 默认实现：DAG 响应缓存读 → 准备入参 → 拓扑执行 →
 * 整图兜底 / 异步写响应缓存与 LKG。
 */
@Service
public class DefaultFlowService implements FlowService {

    private static final Logger log = LoggerFactory.getLogger(DefaultFlowService.class);

    private final DagDefinitionRegistry registry;
    private final TopoScheduler scheduler;
    private final DagRequestPrepareCoordinator prepareCoordinator;
    private final ApplicationContext applicationContext;
    private final CmsFlowFallbackProperties fallbackProperties;
    private final FallbackCacheStore fallbackCacheStore;
    private final SideEffectRunner sideEffectRunner;
    private final ResponseCacheCoordinator responseCacheCoordinator;

    public DefaultFlowService(DagDefinitionRegistry registry,
                              TopoScheduler scheduler,
                              DagRequestPrepareCoordinator prepareCoordinator,
                              ApplicationContext applicationContext,
                              CmsFlowFallbackProperties fallbackProperties,
                              FallbackCacheStore fallbackCacheStore,
                              SideEffectRunner sideEffectRunner,
                              ResponseCacheCoordinator responseCacheCoordinator) {
        this.registry = registry;
        this.scheduler = scheduler;
        this.prepareCoordinator = prepareCoordinator;
        this.applicationContext = applicationContext;
        this.fallbackProperties = fallbackProperties;
        this.fallbackCacheStore = fallbackCacheStore;
        this.sideEffectRunner = sideEffectRunner;
        this.responseCacheCoordinator = responseCacheCoordinator;
    }

    @Override
    public FlowResult<Object> executeAggregate(AggregateCommand command) {
        DagDefinition dag = registry.get(command.dagKey()).orElse(null);
        if (dag == null) {
            return FlowResult.fail("E0404", "unknown dagKey: " + command.dagKey());
        }
        // 0) 前端入参转换 + 前端入参校验
        DagRequestPrepareCoordinator.PrepareOutcome prepared = prepareCoordinator.prepare(dag, command.bizParams());
        if (!prepared.passed()) {
            // 前端入参校验不通过，认为是非合法请求，直接返回对应错误码和错误信息
            return prepared.rejected();
        }

        // 解析DAG的 "失败兜底" 和 "缓存"
        ExecutionContext ctx = new ExecutionContext(command.dagKey(), prepared.preparedBizParams());
        DagFallbackProvider dagFallback = resolveDagFallback(dag);
        DagCacheKeyGenerator cacheKeyGenerator = resolveDagCacheKeyGenerator(dag);

        // 1) 尝试读 DAG 级响应缓存；命中则直接返回（stale 时异步刷新）
        Optional<ResponseCacheCoordinator.ReadResult> dagCached =
                responseCacheCoordinator.tryReadDag(dag.dagKey(), ctx, cacheKeyGenerator);
        if (dagCached.isPresent()) {
            ResponseCacheCoordinator.ReadResult hit = dagCached.get();
            // 缓存已过软过期：先返回旧值，后台重新跑一遍 DAG 并回写缓存
            if (hit.anyStale()) {
                // inflightKey 必须带业务 cacheKey，否则同 DAG 不同 bizKey 会互相抢刷新资格
                responseCacheCoordinator.scheduleDagRefresh(dag.dagKey(), hit.cacheKey(), () -> {
                    ExecutionContext refreshCtx = new ExecutionContext(command.dagKey(), prepared.preparedBizParams());
                    scheduler.execute(dag, refreshCtx);
                    FlowResult<Object> refreshed = refreshCtx.getNodeResult(dag.outputNodeKey());
                    if (isSuccess(refreshed)) {
                        responseCacheCoordinator.writeDagAsync(dag.dagKey(), refreshCtx, refreshed, cacheKeyGenerator);
                    }
                });
            }
            return hit.result();
        }

        // 2) 缓存未命中：按拓扑执行 DAG
        Throwable execError = null;
        try {
            scheduler.execute(dag, ctx);
        } catch (RuntimeException ex) {
            execError = ex;
            log.warn("dag execution failed, try dag fallback: dagKey={}, reason={}", dag.dagKey(), ex.getMessage());
        }

        // 3) 执行抛异常：走整图兜底；兜底失败则返回 E0500
        if (execError != null) {
            FlowResult<Object> fb = safeDagFallback(dagFallback, dag.dagKey(), ctx, execError);
            if (isSuccess(fb)) {
                return fb;
            }
            return FlowResult.fail("E0500", "dag execution failed: " + execError.getMessage());
        }

        // 4) 取输出节点结果；成功则异步写响应缓存 + LKG 兜底缓存
        FlowResult<Object> output = ctx.getNodeResult(dag.outputNodeKey());
        if (isSuccess(output)) {
            responseCacheCoordinator.writeDagAsync(dag.dagKey(), ctx, output, cacheKeyGenerator);
            writeDagFallbackCacheAsync(dag, ctx, output, dagFallback);
            return output;
        }

        // 5) 输出不完整/失败：再走整图兜底；兜底也失败则返回原结果或 E0503
        log.info("dag output incomplete, use dag fallback: dagKey={}", dag.dagKey());
        FlowResult<Object> fb = safeDagFallback(dagFallback, dag.dagKey(), ctx, null);
        if (isSuccess(fb)) {
            return fb;
        }
        return output != null ? output : FlowResult.fail("E0503", "dag output unavailable");
    }

    /**
     * 成功结果异步写入 LKG（Last Known Good）兜底缓存，供后续失败时由 DagFallbackProvider 读取。
     */
    private void writeDagFallbackCacheAsync(DagDefinition dag,
                                            ExecutionContext ctx,
                                            FlowResult<Object> output,
                                            DagFallbackProvider dagFallback) {
        // 兜底总开关关闭 / 无 provider / 无输出 → 不写
        if (!fallbackProperties.isEnabled() || dagFallback == null || output == null) {
            return;
        }
        // 该 DAG 未配置兜底缓存层级，或缺对应层级 TTL → 不写
        Optional<FallbackCacheLevel> levelOpt = fallbackProperties.resolveDagCacheLevel(dag.dagKey());
        Optional<FallbackTtl> ttlOpt = fallbackProperties.resolveDagTtl(dag.dagKey());
        if (levelOpt.isEmpty() || ttlOpt.isEmpty()) {
            return;
        }
        // 写采样，降低热点压力
        if (!ResponseCacheSupport.shouldWrite(fallbackProperties.effectiveWriteSampleRate())) {
            return;
        }
        // 仅成功且非空数据写入 LKG
        if (!output.isSuccess() || output.data() == null) {
            return;
        }
        String key = dagFallback.resolveCacheKey(dag.dagKey(), ctx, output.data());
        if (key == null || key.isBlank()) {
            return;
        }
        FallbackCacheLevel level = levelOpt.get();
        FallbackTtl ttl = ttlOpt.get();
        Object data = output.data();
        sideEffectRunner.execute(() ->
                fallbackCacheStore.put(key, data, ttl.l1Duration(), ttl.l2Duration(),
                        level, FallbackCacheScope.DAG));
    }

    /**
     * 安全调用整图兜底：开关关闭 / provider 为空 / 抛异常 → 统一转成失败结果，不向上抛。
     */
    private FlowResult<Object> safeDagFallback(DagFallbackProvider provider,
                                               String dagKey,
                                               ExecutionContext ctx,
                                               Throwable cause) {
        if (!fallbackProperties.isEnabled() || provider == null) {
            return failResult(cause);
        }
        try {
            FlowResult<Object> fb = provider.fallback(dagKey, ctx, cause);
            if (fb != null) {
                return fb;
            }
        } catch (Throwable t) {
            log.warn("dag fallback provider failed: dagKey={}, reason={}", dagKey, t.getMessage());
        }
        return failResult(cause);
    }

    private DagFallbackProvider resolveDagFallback(DagDefinition dag) {
        Class<? extends DagFallbackProvider> type = dag.fallbackProviderClass();
        if (type == null) {
            return null;
        }
        return applicationContext.getBean(type);
    }

    private DagCacheKeyGenerator resolveDagCacheKeyGenerator(DagDefinition dag) {
        Class<? extends DagCacheKeyGenerator> type = dag.cacheKeyGeneratorClass();
        if (type == null) {
            return null;
        }
        return applicationContext.getBean(type);
    }

    private static boolean isSuccess(FlowResult<Object> result) {
        return result != null && result.isSuccess();
    }

    private static FlowResult<Object> failResult(Throwable cause) {
        String message = cause == null ? "dag fallback unavailable"
                : (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
        return FlowResult.fail("E0503", message);
    }
}
