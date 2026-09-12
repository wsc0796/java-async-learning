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
