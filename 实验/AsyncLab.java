import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Java 21 教学演示。一次只读一课对应的方法；所有外部依赖均用本地等待模拟。 */
public class AsyncLab {
    private static final long START = System.nanoTime();

    public static void main(String[] args) throws Exception {
        String scene = args.length == 0 ? "threads" : args[0];
        switch (scene) {
            case "threads" -> threads();
            case "saturation" -> saturation();
            case "discard" -> discard();
            case "futures" -> futures();
            case "dag" -> dag();
            case "starvation" -> starvation();
            case "failure" -> failure();
            case "timeout" -> timeout();
            case "cancel" -> cancel();
            case "virtual" -> virtual();
            default -> throw new IllegalArgumentException("未知实验：" + scene);
        }
        System.out.println("PASS " + scene + "（演示行为符合预期，不代表学员已经掌握）");
    }

    // 第 01 课：同一任务可以由不同线程执行；Future 可以尚未完成。
    static void threads() {
        Runnable task = () -> log("执行同一个 Runnable");
        task.run();
        ExecutorService pool = Executors.newSingleThreadExecutor(factory("worker-"));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            pool.execute(task);
            var future = CompletableFuture.runAsync(() -> {
                started.countDown();
                await(release);
                log("工作任务被放行");
            }, pool);
            await(started);
            check(!future.isDone(), "放行前 Future 应未完成");
            log("放行前 Future未完成=" + !future.isDone());
            release.countDown();
            future.join();
            log("主线程 join 已返回");
        } finally {
            release.countDown();
            pool.close();
        }
    }

    // 第 02 课：门闩保证任务在观察时都不结束，避免竞争导致容量题不可复现。
    static void saturation() {
        CountDownLatch started = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger rejected = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 3, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2), factory("pool-"), new ThreadPoolExecutor.AbortPolicy());
        try {
            for (int id = 1; id <= 6; id++) {
                int taskId = id;
                try {
                    pool.execute(() -> {
                        log("任务" + taskId + "开始");
                        started.countDown();
                        await(release);
                    });
                    log("已接纳任务" + id);
                } catch (RejectedExecutionException ex) {
                    rejected.incrementAndGet();
                    log("拒绝任务" + id);
                }
            }
            await(started);
            check(pool.getPoolSize() == 3, "应创建3个线程");
            check(pool.getQueue().size() == 2, "应有2个任务排队");
            check(rejected.get() == 1, "应拒绝1个任务");
            log("固定观察点：线程=3，排队=2，拒绝=1");
        } finally {
            release.countDown();
            pool.close();
        }
    }

    // 第 02 课破坏性演示：包装任务被丢弃，没人完成 CompletableFuture。
    static void discard() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), factory("discard-"), new ThreadPoolExecutor.DiscardPolicy());
        try {
            pool.execute(() -> { started.countDown(); await(release); });
            await(started);
            pool.execute(() -> log("排队的普通任务被执行"));
            AtomicBoolean ran = new AtomicBoolean();
            var missing = CompletableFuture.runAsync(() -> ran.set(true), pool);
            try {
                missing.get(80, TimeUnit.MILLISECONDS);
                throw new AssertionError("被丢弃的任务不应完成");
            } catch (TimeoutException expected) {
                check(!missing.isDone() && !ran.get(), "任务必须没执行且Future没完成");
                log("没有拒绝异常，但Future未完成=" + !missing.isDone());
            }
            missing.cancel(false); // 只清理教学句柄，不代表原策略自动取消。
        } finally {
            release.countDown();
            pool.close();
        }
    }

    // 第 03 课：先提交再等待。耗时只展示，不以硬阈值判通过。
    static void futures() {
        try (ExecutorService pool = Executors.newFixedThreadPool(2, factory("future-"))) {
            long begin = System.nanoTime();
            var goods = CompletableFuture.supplyAsync(() -> query("商品", 200), pool);
            var price = CompletableFuture.supplyAsync(() -> query("价格", 350), pool);
            var card = goods.thenCombine(price, (g, p) -> g + "+" + p);
            CompletableFuture.allOf(goods, price).join();
            check(card.join().equals("商品+价格"), "合并结果");
            log("先提交后汇合：" + elapsed(begin) + "ms，结果=" + card.join());
            begin = System.nanoTime();
            var first = CompletableFuture.supplyAsync(() -> query("商品", 200), pool).join();
            var second = CompletableFuture.supplyAsync(() -> query("价格", 350), pool).join();
            log("每次提交后立即等待：" + elapsed(begin) + "ms，结果=" + first + "+" + second);
            var changed = goods.thenApply(String::length);
            var chained = goods.thenCompose(g -> CompletableFuture.supplyAsync(() -> g + "详情", pool));
            check(changed.join() == 2 && chained.join().equals("商品详情"), "转换与串接");
        }
    }

    // 第 04 课：固定三波便于观察；通用 Kahn 算法留给 PracticeDag。
    static void dag() {
        try (ExecutorService scheduler = Executors.newSingleThreadExecutor(factory("dag-"));
             ExecutorService normal = Executors.newFixedThreadPool(2, factory("normal-"));
             ExecutorService slow = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("slow-", 1).factory())) {
            Map<String, String> results = new ConcurrentHashMap<>();
            Map<String, Long> begin = new ConcurrentHashMap<>();
            Map<String, Long> end = new ConcurrentHashMap<>();
            long requestStart = System.nanoTime();
            var whole = CompletableFuture.runAsync(() -> {
                log("进入波次循环");
                CompletableFuture.runAsync(() -> node("content", 100, results, begin, end), normal).join();
                var goods = CompletableFuture.runAsync(() -> node("goods", 200, results, begin, end), normal);
                var price = CompletableFuture.runAsync(() -> node("price", 350, results, begin, end), slow);
                log("调度线程等待第二波");
                CompletableFuture.allOf(goods, price).join();
                CompletableFuture.runAsync(() -> {
                    check(results.containsKey("goods") && results.containsKey("price"), "聚合依赖必须存在");
                    node("assemble", 50, results, begin, end);
                }, normal).join();
            }, scheduler);
            log("模拟请求线程等待整图");
            whole.join();
            for (String middle : List.of("goods", "price")) {
                check(begin.get(middle) >= end.get("content"), "content先结束");
                check(begin.get("assemble") >= end.get(middle), "assemble后开始");
            }
            check(results.size() == 4, "四个节点结果");
            log("整图完成：" + elapsed(requestStart) + "ms，结果=" + results);
        }
    }

    // 第 05 课：200ms 超时用于退出演示，不能误当成消除资源循环等待。
    static void starvation() throws Exception {
        AtomicBoolean timedOut = new AtomicBoolean();
        AtomicBoolean childRan = new AtomicBoolean();
        try (ExecutorService pool = Executors.newSingleThreadExecutor(factory("single-"))) {
            Future<?> parent = pool.submit(() -> {
                Future<?> child = pool.submit(() -> { childRan.set(true); log("子任务终于获得线程"); });
                try {
                    child.get(200, TimeUnit.MILLISECONDS);
                    throw new AssertionError("单线程父任务尚未结束，子任务不应执行");
                } catch (TimeoutException expected) {
                    timedOut.set(true);
                    check(!childRan.get(), "此时子任务仍在排队");
                    log("父任务等待超时：子任务没有可用线程");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new CompletionException(ex);
                } catch (ExecutionException ex) {
                    throw new CompletionException(ex.getCause());
                }
            });
            parent.get(5, TimeUnit.SECONDS);
        }
        check(timedOut.get() && childRan.get(), "父等待退出后，子任务才执行");
    }

    // 第 06 课：allOf 不是“首个失败就完成”。
    static void failure() {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor(factory("failure-"));
        try {
            var bad = CompletableFuture.failedFuture(new IllegalStateException("模拟节点失败"));
            var slow = CompletableFuture.runAsync(() -> { started.countDown(); await(release); }, pool);
            await(started);
            var all = CompletableFuture.allOf(bad, slow);
            check(!all.isDone(), "仍有输入未完成，allOf不应完成");
            log("一个已失败、一个尚未结束：allOf未完成=" + !all.isDone());
            release.countDown();
            try {
                all.join();
                throw new AssertionError("应传播失败");
            } catch (CompletionException ex) {
                check(ex.getCause() instanceof IllegalStateException, "应保留原始原因");
                log("全体结束后看到异常：" + ex.getCause().getMessage());
            }
        } finally {
            release.countDown();
            pool.close();
        }
    }

    // 第 06 课：get超时不改原Future；orTimeout改状态但不终止工作。
    static void timeout() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch ended = new CountDownLatch(1);
        AtomicBoolean actualWorkDone = new AtomicBoolean();
        ExecutorService pool = Executors.newSingleThreadExecutor(factory("timeout-"));
        try {
            var work = CompletableFuture.supplyAsync(() -> {
                started.countDown();
                try { await(release); actualWorkDone.set(true); return "工作真正完成"; }
                finally { ended.countDown(); }
            }, pool);
            await(started);
            try {
                work.get(80, TimeUnit.MILLISECONDS);
                throw new AssertionError("应等待超时");
            } catch (TimeoutException expected) {
                check(!work.isDone(), "get超时不完成原Future");
                log("get等待超时后，Future仍未完成");
            }
            check(work.orTimeout(80, TimeUnit.MILLISECONDS) == work, "orTimeout返回同一个Future");
            try {
                work.join();
                throw new AssertionError("应异常完成");
            } catch (CompletionException ex) {
                check(ex.getCause() instanceof TimeoutException, "超时原因");
            }
            check(!actualWorkDone.get(), "Future已结束，但工作还卡在门闩");
            log("orTimeout后：Future已结束=true，实际工作结束=false");
            release.countDown();
            await(ended);
            check(actualWorkDone.get() && work.isCompletedExceptionally(), "工作仍继续，Future保留超时状态");
            log("放行后实际工作结束=true，Future仍是超时异常状态");
        } finally {
            release.countDown();
            pool.close();
        }
    }

    static void cancel() {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch ended = new CountDownLatch(1);
        AtomicBoolean continued = new AtomicBoolean();
        AtomicBoolean interrupted = new AtomicBoolean();
        ExecutorService pool = Executors.newSingleThreadExecutor(factory("cancel-"));
        try {
            var future = CompletableFuture.runAsync(() -> {
                started.countDown();
                try {
                    await(release);
                    interrupted.set(Thread.currentThread().isInterrupted());
                    continued.set(true);
                } finally { ended.countDown(); }
            }, pool);
            await(started);
            check(future.cancel(true), "应该进入取消状态");
            check(future.isCancelled(), "取消状态可见");
            release.countDown();
            await(ended);
            check(continued.get() && !interrupted.get(), "CompletableFuture取消没有中断本任务");
            log("Future已取消=true，实际任务仍继续=true，中断标志=false");
        } finally {
            release.countDown();
            pool.close();
        }
    }

    // 第 07 课：在提交方拿许可，实际任务退出时归还；提交失败也归还。
    static void virtual() {
        Semaphore permits = new Semaphore(2);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        AtomicBoolean allVirtual = new AtomicBoolean(true);
        try (ExecutorService vt = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("vt-", 1).factory())) {
            Runnable work = () -> {
                int current = active.incrementAndGet();
                peak.accumulateAndGet(current, Math::max);
                if (!Thread.currentThread().isVirtual()) allVirtual.set(false);
                log("isVirtual=" + Thread.currentThread().isVirtual() + "，占用许可");
                started.countDown();
                try { await(release); completed.incrementAndGet(); }
                finally { active.decrementAndGet(); }
            };
            try {
                submitBounded(vt, permits, work);
                submitBounded(vt, permits, work);
                await(started);
                try {
                    submitBounded(vt, permits, work);
                    throw new AssertionError("第三个任务应被拒绝");
                } catch (RejectedExecutionException expected) {
                    log("第三个任务被拒绝，可用许可=" + permits.availablePermits());
                }
            } finally { release.countDown(); }
        }
        check(peak.get() == 2 && completed.get() == 2 && allVirtual.get(), "两个虚拟线程工作且上限为2");
        check(permits.availablePermits() == 2, "完成后归还所有许可");
        // 再验证获得许可后提交失败的释放路径。
        Executor rejectedExecutor = r -> { throw new RejectedExecutionException("模拟执行器已关闭"); };
        try { submitBounded(rejectedExecutor, permits, () -> {}); }
        catch (RejectedExecutionException expected) { log("提交失败已归还许可"); }
        check(permits.availablePermits() == 2, "提交失败后无许可泄漏");
        // 直接执行器便于确定性验证任务抛异常时 finally 的释放路径。
        try {
            submitBounded(Runnable::run, permits, () -> { throw new IllegalStateException("模拟任务失败"); });
            throw new AssertionError("应看到任务异常");
        } catch (IllegalStateException expected) { log("任务失败已归还许可"); }
        check(permits.availablePermits() == 2, "任务失败后无许可泄漏");
        log("结束：峰值占用=" + peak.get() + "，可用许可=" + permits.availablePermits());
    }

    static void submitBounded(Executor executor, Semaphore permits, Runnable task) {
        if (!permits.tryAcquire()) throw new RejectedExecutionException("没有可用许可");
        AtomicBoolean released = new AtomicBoolean();
        Runnable releaseOnce = () -> { if (released.compareAndSet(false, true)) permits.release(); };
        try {
            executor.execute(() -> { try { task.run(); } finally { releaseOnce.run(); } });
        } catch (RuntimeException ex) {
            // 同时支持教学用的同步Executor，确保其任务异常不会导致重复归还。
            releaseOnce.run();
            throw ex;
        }
    }

    static void node(String name, int ms, Map<String, String> results,
                     Map<String, Long> begin, Map<String, Long> end) {
        begin.put(name, System.nanoTime());
        log(name + "开始");
        sleep(ms);
        results.put(name, name + "-ok");
        log(name + "结束");
        end.put(name, System.nanoTime());
    }
    static String query(String name, int ms) { log(name + "查询"); sleep(ms); return name; }
    static ThreadFactory factory(String prefix) { return Thread.ofPlatform().name(prefix, 1).factory(); }
    static void sleep(int ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new CompletionException(ex); }
    }
    static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("教学门闩等待超过5秒");
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new CompletionException(ex); }
    }
    static long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static void log(String message) {
        System.out.printf("%5dms [%-14s] %s%n", elapsed(START), Thread.currentThread().getName(), message);
    }
}
