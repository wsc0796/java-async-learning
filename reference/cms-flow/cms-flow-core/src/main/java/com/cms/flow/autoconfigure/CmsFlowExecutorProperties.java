package com.cms.flow.autoconfigure;

import lombok.Getter;

import lombok.NoArgsConstructor;

import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 线程池配置：{@code cms.flow.executor.*}。
 * dag-scheduler 只做编排；node-invoke / fast / slow 跑节点；side-effect 写 LKG 等可丢任务。
 */

@Getter
@Setter
@ConfigurationProperties(prefix = "cms.flow.executor")
public class CmsFlowExecutorProperties {

    private PlatformPoolProperties dagScheduler = new PlatformPoolProperties(8, 32, 512, "cms-flow-dag-");

    private NodeInvokeProperties nodeInvoke = new NodeInvokeProperties();

    private PlatformPoolProperties fastNodeInvoke = new PlatformPoolProperties(8, 32, 128, "cms-flow-fast-");

    private PlatformPoolProperties sideEffect = new PlatformPoolProperties(2, 4, 128, "cms-flow-side-");

    public void setDagScheduler(PlatformPoolProperties dagScheduler) {
        this.dagScheduler = dagScheduler != null ? dagScheduler : new PlatformPoolProperties(8, 32, 512, "cms-flow-dag-");
    }

    public void setNodeInvoke(NodeInvokeProperties nodeInvoke) {
        this.nodeInvoke = nodeInvoke != null ? nodeInvoke : new NodeInvokeProperties();
    }

    public void setFastNodeInvoke(PlatformPoolProperties fastNodeInvoke) {
        this.fastNodeInvoke = fastNodeInvoke != null
                ? fastNodeInvoke
                : new PlatformPoolProperties(8, 32, 128, "cms-flow-fast-");
    }

    public void setSideEffect(PlatformPoolProperties sideEffect) {
        this.sideEffect = sideEffect != null
                ? sideEffect
                : new PlatformPoolProperties(2, 4, 128, "cms-flow-side-");
    }

    /** 平台线程池参数。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class PlatformPoolProperties {
        private int corePoolSize = 2;
        private int maxPoolSize = 4;
        private int queueCapacity = 256;
        private String threadNamePrefix = "cms-flow-";

        public PlatformPoolProperties(int corePoolSize, int maxPoolSize, int queueCapacity, String threadNamePrefix) {
            this.corePoolSize = corePoolSize;
            this.maxPoolSize = maxPoolSize;
            this.queueCapacity = queueCapacity;
            this.threadNamePrefix = threadNamePrefix;
        }
    }

    /** NORMAL 节点调用池。VT 模式无界；平台池参数仅在 {@code useVirtualThreads=false} 时生效。 */
    @Getter
    @Setter
    public static class NodeInvokeProperties {
        /** true：无界虚拟线程；false：平台池。 */
        private boolean useVirtualThreads = false;
        private int corePoolSize = 16;
        private int maxPoolSize = 32;
        private int queueCapacity = 256;
        private String threadNamePrefix = "cms-flow-invoke-";
    }
}
