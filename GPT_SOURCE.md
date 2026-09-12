# cms-flow参考源码合并版

用于阅读，不是完整可构建项目。每段注明仓库文件路径；事实边界见reference/README.md。


## GoodsCardDag.java

文件：[reference/cms-flow/cms-flow-biz/src/main/java/com/cms/flow/definition/GoodsCardDag.java](reference/cms-flow/cms-flow-biz/src/main/java/com/cms/flow/definition/GoodsCardDag.java)

```java
package com.cms.flow.definition;

import com.cms.flow.cache.key.GoodsCardDagCacheKeyGenerator;
import com.cms.flow.converter.GoodsCardRequestParamConverter;
import com.cms.flow.dag.DagTopology;
import com.cms.flow.dag.EdgeSpec;
import com.cms.flow.dag.FlowDag;
import com.cms.flow.dag.FlowDagDefinition;
import com.cms.flow.fallback.dag.GoodsCardDagFallbackProvider;
import com.cms.flow.prevalidator.GoodsCardPreValidator;
import com.cms.flow.spec.ContentNodeSpec;
import com.cms.flow.spec.GoodsNodeSpec;
import com.cms.flow.spec.PriceNodeSpec;
import com.cms.flow.spec.assemble.AssembleNodeSpec;

import java.util.List;

/**
 * Demo DAG「商品卡片」拓扑声明。
 * contentNode ──┬→ goodsNode ──┐
 *               └→ priceNode ──┴→ assembleNode
 * DAG 级扩展：入参转换 + 前置校验 + 整图兜底 + 整图响应缓存 Key。
 */
@FlowDag(dagKey = GoodsCardDag.DAG_KEY, outputNodeKey = AssembleNodeSpec.ID)
public class GoodsCardDag implements FlowDagDefinition {

    public static final String DAG_KEY = "goodsCard";

    @Override
    public void configure(DagTopology.Builder topology) {
        topology.withRequestParamConverter(GoodsCardRequestParamConverter.class)
                .withPreValidator(GoodsCardPreValidator.class)
                .withFallback(GoodsCardDagFallbackProvider.class)
                .withCacheKeyGenerator(GoodsCardDagCacheKeyGenerator.class)
                .nodes(
                        ContentNodeSpec.spec(),
                        GoodsNodeSpec.spec(),
                        PriceNodeSpec.spec(),
                        AssembleNodeSpec.spec()
                ).edges(List.of(
                        EdgeSpec.link(ContentNodeSpec.ID, GoodsNodeSpec.ID),
                        EdgeSpec.link(ContentNodeSpec.ID, PriceNodeSpec.ID),
                        EdgeSpec.link(GoodsNodeSpec.ID, AssembleNodeSpec.ID),
                        EdgeSpec.link(PriceNodeSpec.ID, AssembleNodeSpec.ID)
                ));
    }
}
```


## PriceNodeSpec.java

文件：[reference/cms-flow/cms-flow-biz/src/main/java/com/cms/flow/spec/PriceNodeSpec.java](reference/cms-flow/cms-flow-biz/src/main/java/com/cms/flow/spec/PriceNodeSpec.java)

```java
package com.cms.flow.spec;

import com.cms.flow.cache.key.SkuIdNodeCacheKeyGenerator;
import com.cms.flow.dag.DagNodeSpec;
import com.cms.flow.executor.InvokeExecutorType;
import com.cms.flow.fallback.node.PriceNodeFallbackProvider;
import com.cms.flow.parser.PriceNodeRequestParser;
import com.cms.flow.parser.PriceNodeResponseParser;

public final class PriceNodeSpec {

    public static final String ID = "priceNode";
    public static final String URL_KEY = "price-batch";

    private PriceNodeSpec() {
    }

    public static DagNodeSpec spec() {
        return DagNodeSpec.builder()
                .id(ID)
                .name("价格")
                .http(URL_KEY, PriceNodeRequestParser.class, PriceNodeResponseParser.class)
                .withInvokeExecutorType(InvokeExecutorType.SLOW)
                .withFallback(PriceNodeFallbackProvider.class)
                .withCacheKeyGenerator(SkuIdNodeCacheKeyGenerator.class)
                .build();
    }
}
```


## application.yml

文件：[reference/cms-flow/cms-flow-biz/src/main/resources/application.yml](reference/cms-flow/cms-flow-biz/src/main/resources/application.yml)

```yaml
server:
  # 应用 HTTP 监听端口
  port: 8080

spring:
  application:
    # Spring Boot 应用名，用于日志、监控和服务标识
    name: cms-flow
  autoconfigure:
    exclude:
      # 禁用 Spring Boot Redis 客户端自动配置；当前示例只使用进程内 L1 缓存
      - org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
      # 禁用 Spring Data Redis Repository 自动配置
      - org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration

management:
  endpoints:
    web:
      exposure:
        # 通过 HTTP 暴露健康检查和 Flow 线程池监控端点
        include: health,cmsFlowExecutors

cms:
  flow:
    http:
      urls:
        # 内容节点的批量查询接口；key 与 ContentNodeSpec 中的 urlKey 对应
        content-batch: http://localhost:8081/stub/content/batch
        # 商品节点的批量查询接口；key 与 GoodsNodeSpec 中的 urlKey 对应
        goods-batch: http://localhost:8081/stub/goods/batch
        # 价格节点的批量查询接口；key 与 PriceNodeSpec 中的 urlKey 对应
        price-batch: http://localhost:8081/stub/price/batch
    executor:
      # DAG 编排线程池，只负责节点调度，不执行具体的下游调用
      dag-scheduler:
        # 常驻核心线程数
        core-pool-size: 8
        # 任务繁忙时允许创建的最大线程数
        max-pool-size: 32
        # 等待执行的任务队列容量
        queue-capacity: 512
      # NORMAL 节点调用池，未指定 FAST/SLOW 的节点默认使用
      # use-virtual-threads: true 时为无界 VT（无背压配置）；false 时用下方平台池参数
      node-invoke:
        # true 使用虚拟线程；false 使用传统平台线程池
        use-virtual-threads: false
        # 以下三项仅在 use-virtual-threads: false 时生效
        core-pool-size: 16
        max-pool-size: 32
        queue-capacity: 256
      # FAST 节点专用平台线程池，用于隔离耗时短、关键链路节点
      fast-node-invoke:
        # 常驻核心线程数
        core-pool-size: 8
        # 任务繁忙时允许创建的最大线程数
        max-pool-size: 32
        # 等待执行的任务队列容量
        queue-capacity: 128
      # SLOW 为无界 VT，无需配置
      # 缓存写入等非主链路副作用任务使用的线程池
      side-effect:
        # 常驻核心线程数
        core-pool-size: 2
        # 任务繁忙时允许创建的最大线程数
        max-pool-size: 4
        # 等待执行的任务队列容量；队列满时副作用任务允许被丢弃
        queue-capacity: 128
    # 失败兜底（LKG，Last Known Good）配置，与成功响应缓存相互独立
    fallback:
      # 失败兜底总开关
      enabled: true
      # 成功结果写入 LKG 的采样万分比；5000 表示约 50%
      write-sample-rate: 5000
      cache:
        # LKG 缓存 key 前缀，避免与其他缓存命名冲突
        key-prefix: "cms-flow:fb"
        # 池仅配置容量；TTL 必须在 dags / nodes 上按 level 显式配置
        dag:
          # 整图 L1 Caffeine 缓存允许保存的最大条目数
          l1-max-size: 1000
        node:
          # 节点 L1 Caffeine 缓存允许保存的最大条目数
          l1-max-size: 10000
      # 按 DAG 配置兜底策略，key 为 dagKey
      dags:
        # goodsCard DAG 的整图及节点兜底策略
        goodsCard:
          # 整图兜底层级：L1=进程内 Caffeine，L2=Redis，L1_L2=两级缓存
          level: L1
          # 整图 L1 兜底数据的有效期，单位秒
          l1-ttl-seconds: 600
          # 本 DAG 使用各节点时，是否启用「节点级」兜底（可与其它 DAG 不同）
          nodes:
            # 内容节点兜底策略
            contentNode:
              # 是否启用该节点的 LKG 读写
              enabled: true
              # 节点兜底缓存层级
              level: L1
              # 节点 L1 兜底数据有效期，单位秒
              l1-ttl-seconds: 300
            # 商品节点兜底策略
            goodsNode:
              # 是否启用该节点的 LKG 读写
              enabled: true
              # 节点兜底缓存层级
              level: L1
              # 节点 L1 兜底数据有效期，单位秒
              l1-ttl-seconds: 300
            # 价格节点兜底策略
            priceNode:
              # 是否启用该节点的 LKG 读写
              enabled: true
              # 节点兜底缓存层级
              level: L1
              # 节点 L1 兜底数据有效期，单位秒
              l1-ttl-seconds: 300
    # 成功路径响应缓存（与 fallback LKG 隔离，前缀 cms-flow:rc）
    cache:
      # 成功响应缓存总开关；false 时下方策略均不生效
      enabled: false
      # TTL 随机抖动比例；0.1 表示在基础 TTL 上打散 10%，防止缓存集中失效
      ttl-jitter-ratio: 0.1
      # 成功结果写缓存的采样万分比；10000 表示全部写入
      write-sample-rate: 10000
      # 成功但 data=null 时使用短 TTL 缓存，防止缓存穿透
      null-cache:
        # 是否缓存空结果
        enabled: true
        # 空结果在 L1 中的有效期，单位秒
        l1-ttl-seconds: 30
        # 空结果在 L2 Redis 中的有效期，单位秒
        l2-ttl-seconds: 60
      # SWR（stale-while-revalidate）后台刷新配置
      refresh:
        # 是否允许对软过期缓存执行后台刷新
        enabled: true
        # 同一缓存 key 两次后台刷新的最小间隔，单位秒
        min-interval-seconds: 30
        # 后台刷新启动的最大随机延迟，单位毫秒，避免并发集中刷新
        start-jitter-ms: 2000
      cache:
        # 成功响应缓存 key 前缀
        key-prefix: "cms-flow:rc"
        # 池仅配置容量；TTL 必须在 dags / nodes 上按 level 显式配置
        dag:
          # 整图 L1 Caffeine 缓存允许保存的最大条目数
          l1-max-size: 1000
        node:
          # 节点 L1 Caffeine 缓存允许保存的最大条目数
          l1-max-size: 10000
      # 按 DAG 配置成功响应缓存策略，key 为 dagKey
      dags:
        # goodsCard DAG 的整图及节点响应缓存策略
        goodsCard:
          # 整图缓存层级：L1=进程内 Caffeine，L2=Redis，L1_L2=两级缓存
          level: L1
          # 整图 L1 缓存硬过期时间，单位秒
          l1-ttl-seconds: 60
          # 软过期时间，单位秒；到期后可返回旧值并后台刷新，须小于硬 TTL
          soft-ttl-seconds: 30
          nodes:
            # 内容节点响应缓存策略
            contentNode:
              # 是否启用该节点的成功响应缓存
              enabled: true
              # 节点响应缓存层级
              level: L1
              # 节点 L1 缓存有效期，单位秒
              l1-ttl-seconds: 30
            # 商品节点响应缓存策略
            goodsNode:
              # 是否启用该节点的成功响应缓存
              enabled: true
              # 节点响应缓存层级
              level: L1
              # 节点 L1 缓存有效期，单位秒
              l1-ttl-seconds: 30
            # 价格节点响应缓存策略
            priceNode:
              # 是否启用该节点的成功响应缓存
              enabled: true
              # 节点响应缓存层级
              level: L1
              # 节点 L1 缓存有效期，单位秒
              l1-ttl-seconds: 30
    # Resilience4j 熔断配置，保护下游异常时的主调用链路
    resilience:
      # 熔断功能总开关
      enabled: true
      # 统计最近调用结果的滑动窗口大小
      sliding-window-size: 10
      # 失败率阈值，单位百分比；达到 50% 时打开熔断器
      failure-rate-threshold: 50
      # 熔断器 OPEN 状态持续时间，单位毫秒，之后进入 HALF_OPEN
      wait-duration-in-open-state-ms: 5000
      # 至少积累多少次调用后才开始计算失败率
      minimum-number-of-calls: 5
      # HALF_OPEN 状态允许的探测调用次数
      permitted-number-of-calls-in-half-open-state: 3

logging:
  level:
    # cms-flow 项目包的日志级别
    com.cms.flow: INFO
```


## CmsFlowAutoConfiguration.java

文件：[reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/autoconfigure/CmsFlowAutoConfiguration.java](reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/autoconfigure/CmsFlowAutoConfiguration.java)

```java
package com.cms.flow.autoconfigure;

import com.cms.flow.cache.CompositeFallbackCacheStore;
import com.cms.flow.cache.FallbackCacheStore;
import com.cms.flow.cache.L1CaffeineFallbackCacheStore;
import com.cms.flow.cache.RedisFallbackCacheStore;
import com.cms.flow.cache.response.CompositeResponseCacheStore;
import com.cms.flow.cache.response.L1CaffeineResponseCacheStore;
import com.cms.flow.cache.response.RedisResponseCacheStore;
import com.cms.flow.cache.response.ResponseCacheCoordinator;
import com.cms.flow.cache.response.ResponseCacheRefreshCoordinator;
import com.cms.flow.cache.response.ResponseCacheStore;
import com.cms.flow.executor.FlowExecutorMetrics;
import com.cms.flow.executor.FlowExecutorRegistry;
import com.cms.flow.executor.NodeInvokeExecutorResolver;
import com.cms.flow.executor.SideEffectRunner;
import com.cms.flow.resilience.NodeResilienceGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 框架自动装配：注册 DAG 引擎、调度器、节点执行器、线程池、熔断、兜底缓存与响应缓存。
 * 业务模块只需引入 {@code cms-flow-core} 依赖，并在自身包路径下声明 {@code @FlowDag} 与 SPI 实现。
 */
@AutoConfiguration
@EnableConfigurationProperties({
        CmsFlowHttpProperties.class,
        CmsFlowFallbackProperties.class,
        CmsFlowCacheProperties.class,
        CmsFlowResilienceProperties.class,
        CmsFlowExecutorProperties.class
})
@ComponentScan(basePackages = {
        "com.cms.flow.dag",
        "com.cms.flow.engine",
        "com.cms.flow.nodetype",
        "com.cms.flow.executor"
})
public class CmsFlowAutoConfiguration {

    @Bean
    RestClient.Builder cmsFlowRestClientBuilder(CmsFlowHttpProperties props) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(props.getReadTimeoutMs()));
        return RestClient.builder().requestFactory(requestFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    NodeResilienceGuard nodeResilienceGuard(CmsFlowResilienceProperties properties) {
        return new NodeResilienceGuard(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    FlowExecutorMetrics flowExecutorMetrics() {
        return new FlowExecutorMetrics();
    }

    @Bean(destroyMethod = "destroy")
    @ConditionalOnMissingBean
    FlowExecutorRegistry flowExecutorRegistry(CmsFlowExecutorProperties properties,
                                              FlowExecutorMetrics metrics) {
        return new FlowExecutorRegistry(properties, metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    NodeInvokeExecutorResolver nodeInvokeExecutorResolver(FlowExecutorRegistry registry) {
        return new NodeInvokeExecutorResolver(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    SideEffectRunner sideEffectRunner(FlowExecutorRegistry registry) {
        return new SideEffectRunner(registry);
    }

    @Bean
    @ConditionalOnMissingBean(FallbackCacheStore.class)
    FallbackCacheStore fallbackCacheStore(CmsFlowFallbackProperties fallbackProperties,
                                          ObjectProvider<RedisFallbackCacheStore> redisStoreProvider) {
        FallbackCacheStore dagL1 = new L1CaffeineFallbackCacheStore(
                fallbackProperties.getCache().getDag().getL1MaxSize());
        FallbackCacheStore nodeL1 = new L1CaffeineFallbackCacheStore(
                fallbackProperties.getCache().getNode().getL1MaxSize());
        return new CompositeFallbackCacheStore(dagL1, nodeL1, redisStoreProvider.getIfAvailable());
    }

    /**
     * 仅在 classpath + 容器均存在 {@link StringRedisTemplate} 时注册 L2 兜底 Store。
     */
    @Bean
    @ConditionalOnClass(StringRedisTemplate.class)
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnMissingBean(RedisFallbackCacheStore.class)
    RedisFallbackCacheStore redisFallbackCacheStore(StringRedisTemplate redisTemplate,
                                                    ObjectProvider<ObjectMapper> objectMapperProvider) {
        ObjectMapper mapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        return new RedisFallbackCacheStore(redisTemplate, mapper);
    }

    @Bean
    @ConditionalOnMissingBean(ResponseCacheStore.class)
    ResponseCacheStore responseCacheStore(CmsFlowCacheProperties cacheProperties,
                                          ObjectProvider<RedisResponseCacheStore> redisStoreProvider) {
        ResponseCacheStore dagL1 = new L1CaffeineResponseCacheStore(
                cacheProperties.getCache().getDag().getL1MaxSize());
        ResponseCacheStore nodeL1 = new L1CaffeineResponseCacheStore(
                cacheProperties.getCache().getNode().getL1MaxSize());
        return new CompositeResponseCacheStore(dagL1, nodeL1, redisStoreProvider.getIfAvailable());
    }

    /**
     * 仅在 classpath + 容器均存在 {@link StringRedisTemplate} 时注册 L2 响应缓存 Store。
     */
    @Bean
    @ConditionalOnClass(StringRedisTemplate.class)
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnMissingBean(RedisResponseCacheStore.class)
    RedisResponseCacheStore redisResponseCacheStore(StringRedisTemplate redisTemplate,
                                                    ObjectProvider<ObjectMapper> objectMapperProvider) {
        ObjectMapper mapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        return new RedisResponseCacheStore(redisTemplate, mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    ResponseCacheRefreshCoordinator responseCacheRefreshCoordinator(
            SideEffectRunner sideEffectRunner,
            CmsFlowCacheProperties cacheProperties) {
        CmsFlowCacheProperties.Refresh refresh = cacheProperties.getRefresh();
        return new ResponseCacheRefreshCoordinator(
                sideEffectRunner,
                refresh.isEnabled(),
                refresh.getMinIntervalSeconds(),
                refresh.getStartJitterMs());
    }

    @Bean
    @ConditionalOnMissingBean
    ResponseCacheCoordinator responseCacheCoordinator(CmsFlowCacheProperties cacheProperties,
                                                      ResponseCacheStore responseCacheStore,
                                                      SideEffectRunner sideEffectRunner,
                                                      ResponseCacheRefreshCoordinator refreshCoordinator) {
        return new ResponseCacheCoordinator(
                cacheProperties, responseCacheStore, sideEffectRunner, refreshCoordinator);
    }
}
```


## CmsFlowExecutorProperties.java

文件：[reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/autoconfigure/CmsFlowExecutorProperties.java](reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/autoconfigure/CmsFlowExecutorProperties.java)

```java
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
```


## CmsFlowHttpProperties.java

文件：[reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/autoconfigure/CmsFlowHttpProperties.java](reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/autoconfigure/CmsFlowHttpProperties.java)

```java
package com.cms.flow.autoconfigure;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP 叶子节点下游配置。
 * Spec 只声明配置名（urlKey），完整绝对 URL 写在 {@code cms.flow.http.urls.<name>}。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "cms.flow.http")
public class CmsFlowHttpProperties {

    /**
     * urlKey → 绝对 URL。
     * 例如 {@code urls.content-batch: http://localhost:8081/stub/content/batch}。
     */
    private Map<String, String> urls = new LinkedHashMap<>();

    /** 连接/读取超时（毫秒）。 */
    private int connectTimeoutMs = 2000;
    private int readTimeoutMs = 3000;

    public void setUrls(Map<String, String> urls) {
        this.urls = urls != null ? urls : new LinkedHashMap<>();
    }

    /**
     * 按 Spec 中的 urlKey 解析绝对 URL。
     *
     * @throws IllegalArgumentException 未配置或值为空
     */
    public String requireUrl(String urlKey) {
        if (urlKey == null || urlKey.isBlank()) {
            throw new IllegalArgumentException("http urlKey must not be blank");
        }
        String url = urls.get(urlKey);
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException(
                    "missing cms.flow.http.urls." + urlKey + " (absolute URL required)");
        }
        return url.trim();
    }
}
```


## FlowDag.java

文件：[reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/dag/FlowDag.java](reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/dag/FlowDag.java)

```java
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
```


## FlowDagDefinition.java

文件：[reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/dag/FlowDagDefinition.java](reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/dag/FlowDagDefinition.java)

```java
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
```


## FlowDagDefinitionCollector.java

文件：[reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/dag/FlowDagDefinitionCollector.java](reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/dag/FlowDagDefinitionCollector.java)

```java
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
```


## DefaultFlowService.java

文件：[reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/engine/DefaultFlowService.java](reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/engine/DefaultFlowService.java)

```java
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
```


## ExecutionContext.java

文件：[reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/engine/ExecutionContext.java](reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/engine/ExecutionContext.java)

```java
package com.cms.flow.engine;

import com.cms.flow.api.FlowResult;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单次聚合调用的共享执行状态。
 * 核心职责：
 *   保存 bizParams（可为原始 Map，或经 Converter 后的强类型）
 *   按 nodeId 存放各节点产出：{@code FlowResult}
 *   下游节点通过 {@link #getNodeResult} 读取上游结果
 * 节点间并行写不同 nodeId，因此 nodeResults 使用 ConcurrentHashMap。
 */
public final class ExecutionContext {

    private final String dagKey;
    private final Object bizParams;
    /** nodeId → 该节点对本请求的结果 */
    private final Map<String, FlowResult<Object>> nodeResults = new ConcurrentHashMap<>();

    public ExecutionContext(String dagKey, Object bizParams) {
        this.dagKey = dagKey;
        this.bizParams = bizParams;
    }

    public String dagKey() {
        return dagKey;
    }

    /**
     * 取业务入参（Map 形态）。
     * 若已转换为强类型，返回 empty Map——请改用 {@link #getBizParamsAs(Class)}。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getBizParams() {
        if (bizParams instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    /**
     * 取强类型业务入参；类型不匹配或不存在时返回 null。
     */
    public <T> T getBizParamsAs(Class<T> type) {
        if (type.isInstance(bizParams)) {
            return type.cast(bizParams);
        }
        return null;
    }

    /** 节点执行完毕后写入本节点结果。 */
    public void putNodeResult(String nodeId, FlowResult<Object> result) {
        nodeResults.put(nodeId, result);
    }

    /** 取某节点对本请求的结果；不存在则返回 null。 */
    public FlowResult<Object> getNodeResult(String nodeId) {
        return nodeResults.get(nodeId);
    }
}
```


## TopoScheduler.java

文件：[reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/engine/TopoScheduler.java](reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/engine/TopoScheduler.java)

```java
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
```


## BoundedVirtualExecutor.java

文件：[reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/executor/BoundedVirtualExecutor.java](reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/executor/BoundedVirtualExecutor.java)

```java
package com.cms.flow.executor;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 虚拟线程执行器 + Semaphore 背压：避免 VT 无限扇出打满下游。
 */
public final class BoundedVirtualExecutor implements Executor, AutoCloseable {

    private final ExecutorService virtualThreads;
    private final Semaphore permits;
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicLong rejected = new AtomicLong();
    private final String name;

    public BoundedVirtualExecutor(String name, int maxConcurrency) {
        this.name = Objects.requireNonNull(name, "name");
        int permitsCount = Math.max(1, maxConcurrency);
        this.permits = new Semaphore(permitsCount);
        this.virtualThreads = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public void execute(Runnable command) {
        if (!permits.tryAcquire()) {
            rejected.incrementAndGet();
            throw new RejectedExecutionException(name + " saturated: max concurrency reached");
        }
        active.incrementAndGet();
        try {
            virtualThreads.execute(() -> {
                try {
                    command.run();
                } finally {
                    active.decrementAndGet();
                    permits.release();
                }
            });
        } catch (RuntimeException ex) {
            active.decrementAndGet();
            permits.release();
            throw ex;
        }
    }

    public int activeCount() {
        return active.get();
    }

    public int availablePermits() {
        return permits.availablePermits();
    }

    public long rejectedCount() {
        return rejected.get();
    }

    public String name() {
        return name;
    }

    @Override
    public void close() {
        virtualThreads.shutdown();
        try {
            if (!virtualThreads.awaitTermination(5, TimeUnit.SECONDS)) {
                virtualThreads.shutdownNow();
            }
        } catch (InterruptedException e) {
            virtualThreads.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
```


## CmsFlowExecutorEndpoint.java

文件：[reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/executor/CmsFlowExecutorEndpoint.java](reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/executor/CmsFlowExecutorEndpoint.java)

```java
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
```


## FlowExecutorRegistry.java

文件：[reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/executor/FlowExecutorRegistry.java](reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/executor/FlowExecutorRegistry.java)

```java
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
```


## MdcPropagatingExecutor.java

文件：[reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/executor/MdcPropagatingExecutor.java](reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/executor/MdcPropagatingExecutor.java)

```java
package com.cms.flow.executor;

import org.slf4j.MDC;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * 跨线程复制 SLF4J MDC，避免虚拟线程/池线程丢 Trace 上下文。
 */
public final class MdcPropagatingExecutor implements Executor {

    private final Executor delegate;

    public MdcPropagatingExecutor(Executor delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void execute(Runnable command) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        delegate.execute(() -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (context == null || context.isEmpty()) {
                MDC.clear();
            } else {
                MDC.setContextMap(context);
            }
            try {
                command.run();
            } finally {
                if (previous == null || previous.isEmpty()) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previous);
                }
            }
        });
    }

    public Executor delegate() {
        return delegate;
    }
}
```


## NodeInvokeExecutorResolver.java

文件：[reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/executor/NodeInvokeExecutorResolver.java](reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/executor/NodeInvokeExecutorResolver.java)

```java
package com.cms.flow.executor;

import com.cms.flow.dag.DagNodeSpec;
import com.cms.flow.dag.NodeType;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * 按节点 {@link InvokeExecutorType} 解析调用池；AGGREGATION 始终走 NORMAL。
 */
public class NodeInvokeExecutorResolver {

    private final FlowExecutorRegistry registry;

    public NodeInvokeExecutorResolver(FlowExecutorRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public Executor resolve(DagNodeSpec spec) {
        Objects.requireNonNull(spec, "spec");
        if (spec.type() == NodeType.AGGREGATION) {
            return registry.nodeInvokeNormal();
        }
        InvokeExecutorType type = spec.invokeExecutorType();
        if (type == null) {
            type = InvokeExecutorType.NORMAL;
        }
        return switch (type) {
            case FAST -> registry.fastNodeInvoke();
            case SLOW -> registry.slowNodeInvoke();
            case NORMAL -> registry.nodeInvokeNormal();
        };
    }
}
```


## SideEffectRunner.java

文件：[reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/executor/SideEffectRunner.java](reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/executor/SideEffectRunner.java)

```java
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
```


## VirtualThreadExecutor.java

文件：[reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/executor/VirtualThreadExecutor.java](reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/executor/VirtualThreadExecutor.java)

```java
package com.cms.flow.executor;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 无界虚拟线程执行器：每个任务一个虚拟线程，不做 Semaphore 背压。
 */
public final class VirtualThreadExecutor implements Executor, AutoCloseable {

    private final ExecutorService virtualThreads;
    private final AtomicInteger active = new AtomicInteger();
    private final String name;

    public VirtualThreadExecutor(String name) {
        this.name = Objects.requireNonNull(name, "name");
        this.virtualThreads = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public void execute(Runnable command) {
        active.incrementAndGet();
        try {
            virtualThreads.execute(() -> {
                try {
                    command.run();
                } finally {
                    active.decrementAndGet();
                }
            });
        } catch (RuntimeException ex) {
            active.decrementAndGet();
            throw ex;
        }
    }

    public int activeCount() {
        return active.get();
    }

    public String name() {
        return name;
    }

    @Override
    public void close() {
        virtualThreads.shutdown();
        try {
            if (!virtualThreads.awaitTermination(5, TimeUnit.SECONDS)) {
                virtualThreads.shutdownNow();
            }
        } catch (InterruptedException e) {
            virtualThreads.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
```


## HttpNodeExecutor.java

文件：[reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/nodetype/HttpNodeExecutor.java](reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/nodetype/HttpNodeExecutor.java)

```java
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
```


## NodeRequestParser.java

文件：[reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/spi/NodeRequestParser.java](reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/spi/NodeRequestParser.java)

```java
package com.cms.flow.spi;

import com.cms.flow.engine.ExecutionContext;

/**
 * 叶子节点请求组装器：从 bizParams 或上游节点结果，拼出下游调用参数。
 * 返回下游 DTO；HTTP 发往下游时会包成单元素 List（兼容 batch stub）。
 * 返回 null 表示无法组装（例如上游缺失），执行器将软失败而不调用下游。
 */
@FunctionalInterface
public interface NodeRequestParser {

    /**
     * @return 下游业务请求对象；null 表示跳过下游调用并软失败
     */
    Object build(String nodeId, ExecutionContext ctx);
}
```


## TopoSchedulerTest.java

文件：[reference/cms-flow/cms-flow-core/src/test/java/com/cms/flow/engine/TopoSchedulerTest.java](reference/cms-flow/cms-flow-core/src/test/java/com/cms/flow/engine/TopoSchedulerTest.java)

```java
package com.cms.flow.engine;

import com.cms.flow.api.FlowResult;
import com.cms.flow.dag.DagDefinition;
import com.cms.flow.dag.DagNodeSpec;
import com.cms.flow.dag.EdgeSpec;
import com.cms.flow.executor.FlowExecutorRegistry;
import com.cms.flow.executor.NodeInvokeExecutorResolver;
import com.cms.flow.nodetype.AggregationNodeExecutor;
import com.cms.flow.nodetype.HttpNodeExecutor;
import com.cms.flow.spi.AggregatorHandler;
import com.cms.flow.spi.NodeRequestParser;
import com.cms.flow.spi.NodeResponseParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TopoSchedulerTest {

    private final HttpNodeExecutor http = mock(HttpNodeExecutor.class);
    private final AggregationNodeExecutor aggregation = mock(AggregationNodeExecutor.class);
    private final FlowExecutorRegistry registry = mock(FlowExecutorRegistry.class);
    private final NodeInvokeExecutorResolver resolver = mock(NodeInvokeExecutorResolver.class);

    @Test
    void executesDagTopologicallyAndCollectsOutput() {
        Executor direct = Runnable::run;
        when(registry.dagScheduler()).thenReturn(direct);
        when(resolver.resolve(any())).thenReturn(direct);

        doAnswer(invocation -> {
            DagNodeSpec spec = invocation.getArgument(0);
            ExecutionContext ctx = invocation.getArgument(1);
            ctx.putNodeResult(spec.id(), FlowResult.ok(spec.id()));
            return null;
        }).when(http).execute(any(), any());

        doAnswer(invocation -> {
            DagNodeSpec spec = invocation.getArgument(0);
            ExecutionContext ctx = invocation.getArgument(1);
            ctx.putNodeResult(spec.id(), FlowResult.ok(spec.id()));
            return null;
        }).when(aggregation).execute(any(), any());

        TopoScheduler scheduler = new TopoScheduler(http, aggregation, registry, resolver);
        ExecutionContext ctx = new ExecutionContext("test", Map.of());
        scheduler.execute(buildDag(), ctx);

        assertThat(ctx.getNodeResult("out")).isNotNull();
        assertThat(ctx.getNodeResult("out").isSuccess()).isTrue();
        assertThat(ctx.getNodeResult("out").data()).isEqualTo("out");

        verify(http).execute(argThat(spec -> spec.id().equals("a")), any());
        verify(http).execute(argThat(spec -> spec.id().equals("b")), any());
        verify(http).execute(argThat(spec -> spec.id().equals("c")), any());
        verify(aggregation).execute(argThat(spec -> spec.id().equals("out")), any());
    }

    @Test
    void cycleIsDetectedAsFailure() {
        Executor direct = Runnable::run;
        when(registry.dagScheduler()).thenReturn(direct);
        when(resolver.resolve(any())).thenReturn(direct);

        DagNodeSpec a = httpNode("a");
        DagNodeSpec b = httpNode("b");
        DagDefinition cyclic = new DagDefinition("c", "a",
                Map.of("a", a, "b", b),
                List.of(EdgeSpec.link("a", "b"), EdgeSpec.link("b", "a")),
                List.of(), null, null, null);

        TopoScheduler scheduler = new TopoScheduler(http, aggregation, registry, resolver);
        assertThatThrownBy(() -> scheduler.execute(cyclic, new ExecutionContext("c", Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cycle");
    }

    private static DagDefinition buildDag() {
        DagNodeSpec a = httpNode("a");
        DagNodeSpec b = httpNode("b");
        DagNodeSpec c = httpNode("c");
        DagNodeSpec out = DagNodeSpec.builder().id("out").aggregation(TestAggregator.class).build();
        Map<String, DagNodeSpec> nodes = Map.of("a", a, "b", b, "c", c, "out", out);
        List<EdgeSpec> edges = List.of(
                EdgeSpec.link("a", "b"),
                EdgeSpec.link("a", "c"),
                EdgeSpec.link("b", "out"),
                EdgeSpec.link("c", "out"));
        return new DagDefinition("test", "out", nodes, edges, List.of(), null, null, null);
    }

    private static DagNodeSpec httpNode(String id) {
        return DagNodeSpec.builder()
                .id(id)
                .http("http://example.com", TestRequestParser.class, TestResponseParser.class)
                .build();
    }

    public static class TestRequestParser implements NodeRequestParser {
        @Override
        public Object build(String nodeId, ExecutionContext ctx) {
            return null;
        }
    }

    public static class TestResponseParser implements NodeResponseParser {
        @Override
        public FlowResult<Object> parse(String nodeId, ExecutionContext ctx, Object rawResponse) {
            return FlowResult.ok(null);
        }
    }

    public static class TestAggregator implements AggregatorHandler {
        @Override
        public FlowResult<Object> aggregate(String nodeId, ExecutionContext ctx) {
            return FlowResult.ok(null);
        }
    }
}
```
