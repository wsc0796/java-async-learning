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
