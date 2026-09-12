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
