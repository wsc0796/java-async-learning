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
