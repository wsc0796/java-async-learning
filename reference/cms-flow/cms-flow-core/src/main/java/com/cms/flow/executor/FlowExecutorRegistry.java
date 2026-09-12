package com.cms.flow.executor;

import com.cms.flow.autoconfigure.CmsFlowExecutorProperties;
import com.cms.flow.autoconfigure.CmsFlowExecutorProperties.NodeInvokeProperties;
import com.cms.flow.autoconfigure.CmsFlowExecutorProperties.PlatformPoolProperties;
import org.springframework.beans.factory.DisposableBean;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 持有并生命周期管理各职责线程池；提供 metrics 快照。
 */
public class FlowExecutorRegistry implements DisposableBean {

    private final CmsFlowExecutorProperties properties;
    private final FlowExecutorMetrics metrics;

    private final ThreadPoolExecutor dagSchedulerRaw;
    private final Executor dagScheduler;
    private final AtomicLong dagSchedulerRejected = new AtomicLong();

    private final Object nodeInvokeNormalRaw;
    private final Executor nodeInvokeNormal;
    private final AtomicLong nodeInvokeRejected = new AtomicLong();

    private volatile ThreadPoolExecutor fastNodeInvokeRaw;
    private volatile Executor fastNodeInvoke;
    private final AtomicLong fastRejected = new AtomicLong();
    private final Object fastLock = new Object();

    private volatile VirtualThreadExecutor slowNodeInvokeRaw;
    private volatile Executor slowNodeInvoke;
    private final Object slowLock = new Object();

    private final ThreadPoolExecutor sideEffectRaw;
    private final Executor sideEffect;
    private final AtomicLong sideEffectRejected = new AtomicLong();

    public FlowExecutorRegistry(CmsFlowExecutorProperties properties, FlowExecutorMetrics metrics) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.metrics = Objects.requireNonNull(metrics, "metrics");

        this.dagSchedulerRaw = newPlatformPool(
                properties.getDagScheduler(),
                countingAbort(dagSchedulerRejected, "dag-scheduler"));
        this.dagScheduler = wrap(dagSchedulerRaw);

        NodeInvokeProperties invoke = properties.getNodeInvoke();
        if (invoke.isUseVirtualThreads()) {
            VirtualThreadExecutor vt = new VirtualThreadExecutor("nodeInvoke");
            this.nodeInvokeNormalRaw = vt;
            this.nodeInvokeNormal = wrap(vt);
        } else {
            PlatformPoolProperties pp = new PlatformPoolProperties(
                    invoke.getCorePoolSize(),
                    invoke.getMaxPoolSize(),
                    invoke.getQueueCapacity(),
                    invoke.getThreadNamePrefix());
            ThreadPoolExecutor pool = newPlatformPool(pp, countingAbort(nodeInvokeRejected, "node-invoke"));
            this.nodeInvokeNormalRaw = pool;
            this.nodeInvokeNormal = wrap(pool);
        }

        this.sideEffectRaw = newPlatformPool(
                properties.getSideEffect(),
                (r, executor) -> {
                    sideEffectRejected.incrementAndGet();
                    metrics.incrementSideEffectDropped();
                });
        this.sideEffect = wrap(sideEffectRaw);
    }

    public Executor dagScheduler() {
        return dagScheduler;
    }

    public Executor nodeInvokeNormal() {
        return nodeInvokeNormal;
    }

    /** 懒建 FAST 平台池。 */
    public Executor fastNodeInvoke() {
        Executor existing = fastNodeInvoke;
        if (existing != null) {
            return existing;
        }
        synchronized (fastLock) {
            if (fastNodeInvoke == null) {
                ThreadPoolExecutor pool = newPlatformPool(
                        properties.getFastNodeInvoke(),
                        countingAbort(fastRejected, "fast-node-invoke"));
                fastNodeInvokeRaw = pool;
                fastNodeInvoke = wrap(pool);
            }
            return fastNodeInvoke;
        }
    }

    /** 懒建 SLOW 无界 VT。 */
    public Executor slowNodeInvoke() {
        Executor existing = slowNodeInvoke;
        if (existing != null) {
            return existing;
        }
        synchronized (slowLock) {
            if (slowNodeInvoke == null) {
                VirtualThreadExecutor vt = new VirtualThreadExecutor("slowNodeInvoke");
                slowNodeInvokeRaw = vt;
                slowNodeInvoke = wrap(vt);
            }
            return slowNodeInvoke;
        }
    }

    public Executor sideEffect() {
        return sideEffect;
    }

    public FlowExecutorMetrics metrics() {
        return metrics;
    }

    /** 刷新并返回 metrics 快照。 */
    public Map<String, FlowExecutorMetrics.PoolSnapshot> snapshot() {
        Map<String, FlowExecutorMetrics.PoolSnapshot> map = new LinkedHashMap<>();
        map.put("dagScheduler", platformSnapshot(dagSchedulerRaw, dagSchedulerRejected.get()));
        map.put("nodeInvoke", snapshotNormal());
        if (fastNodeInvokeRaw != null) {
            map.put("fastNodeInvoke", platformSnapshot(fastNodeInvokeRaw, fastRejected.get()));
        }
        if (slowNodeInvokeRaw != null) {
            map.put("slowNodeInvoke", virtualSnapshot(slowNodeInvokeRaw));
        }
        map.put("sideEffect", platformSnapshot(sideEffectRaw, sideEffectRejected.get()));
        metrics.replaceSnapshot(map);
        return map;
    }

    private FlowExecutorMetrics.PoolSnapshot snapshotNormal() {
        if (nodeInvokeNormalRaw instanceof VirtualThreadExecutor vt) {
            return virtualSnapshot(vt);
        }
        return platformSnapshot((ThreadPoolExecutor) nodeInvokeNormalRaw, nodeInvokeRejected.get());
    }

    private static FlowExecutorMetrics.PoolSnapshot virtualSnapshot(VirtualThreadExecutor vt) {
        return new FlowExecutorMetrics.PoolSnapshot(
                "virtual",
                vt.activeCount(),
                0,
                0L,
                null,
                null
        );
    }

    private static FlowExecutorMetrics.PoolSnapshot platformSnapshot(ThreadPoolExecutor pool, long rejected) {
        return new FlowExecutorMetrics.PoolSnapshot(
                "platform",
                pool.getActiveCount(),
                pool.getQueue().size(),
                rejected,
                null,
                pool.getMaximumPoolSize()
        );
    }

    @Override
    public void destroy() {
        shutdownPlatform(dagSchedulerRaw);
        if (nodeInvokeNormalRaw instanceof VirtualThreadExecutor vt) {
            vt.close();
        } else if (nodeInvokeNormalRaw instanceof ThreadPoolExecutor pool) {
            shutdownPlatform(pool);
        }
        if (fastNodeInvokeRaw != null) {
            shutdownPlatform(fastNodeInvokeRaw);
        }
        if (slowNodeInvokeRaw != null) {
            slowNodeInvokeRaw.close();
        }
        shutdownPlatform(sideEffectRaw);
    }

    private static Executor wrap(Executor delegate) {
        return new MdcPropagatingExecutor(delegate);
    }

    private static ThreadPoolExecutor newPlatformPool(PlatformPoolProperties props,
                                                      RejectedExecutionHandler handler) {
        int core = Math.max(1, props.getCorePoolSize());
        int max = Math.max(core, props.getMaxPoolSize());
        int queue = Math.max(1, props.getQueueCapacity());
        return new ThreadPoolExecutor(
                core, max, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queue),
                namedFactory(props.getThreadNamePrefix()),
                handler
        );
    }

    private static RejectedExecutionHandler countingAbort(AtomicLong counter, String name) {
        return (r, executor) -> {
            counter.incrementAndGet();
            throw new RejectedExecutionException(name + " saturated");
        };
    }

    private static RejectedExecutionHandler countingDiscard(AtomicLong counter) {
        return (r, executor) -> counter.incrementAndGet();
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        String p = prefix == null || prefix.isBlank() ? "cms-flow-" : prefix;
        return r -> {
            Thread t = new Thread(r);
            t.setName(p + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    private static void shutdownPlatform(ThreadPoolExecutor pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
