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
