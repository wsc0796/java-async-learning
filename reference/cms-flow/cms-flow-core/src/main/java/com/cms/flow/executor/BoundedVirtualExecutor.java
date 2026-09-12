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
