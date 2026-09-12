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
