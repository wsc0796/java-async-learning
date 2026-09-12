import java.util.*;
import java.util.concurrent.*;

/** 第08课：只补六个 TODO 方法。当前文件能编译，但未实现时检查会失败。 */
public class PracticeDag {
    record Edge(String from, String to) {}

    // 已提供算法骨架；普通Map由调用本方法的调度线程独占。
    static void run(Map<String, Runnable> tasks, List<Edge> edges, Executor nodePool) {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> successors = new LinkedHashMap<>();
        tasks.keySet().forEach(id -> {
            indegree.put(id, 0);
            successors.put(id, new ArrayList<>());
        });
        fillGraph(edges, indegree, successors);
        List<String> current = findReady(indegree);
        int finished = 0;
        while (!current.isEmpty()) {
            List<CompletableFuture<Void>> wave = submitWave(current, tasks, nodePool);
            finished += waitWave(wave, current.size());
            current = releaseNext(current, indegree, successors);
        }
        verifyFinished(finished, tasks.size());
    }

    static void fillGraph(List<Edge> edges, Map<String, Integer> indegree,
                          Map<String, List<String>> successors) {
        // TODO 1：遍历每条边，登记后继，并让终点入度 +1。
        throw new UnsupportedOperationException("TODO 1：建立入度和后继表");
    }

    static List<String> findReady(Map<String, Integer> indegree) {
        // TODO 2：返回当前入度为0的节点列表。不要修改indegree本身。
        throw new UnsupportedOperationException("TODO 2：找到第一波");
    }

    static List<CompletableFuture<Void>> submitWave(List<String> current,
            Map<String, Runnable> tasks, Executor nodePool) {
        // TODO 3：每个任务交给给定nodePool，收集Future；这里不要逐项join。
        throw new UnsupportedOperationException("TODO 3：提交整波任务");
    }

    static int waitWave(List<CompletableFuture<Void>> wave, int currentSize) {
        // TODO 4：等所有Future结束；失败应传播；成功后返回本波节点数。
        throw new UnsupportedOperationException("TODO 4：等待波次完成");
    }

    static List<String> releaseNext(List<String> current, Map<String, Integer> indegree,
                                    Map<String, List<String>> successors) {
        // TODO 5：对本波节点的后继减入度；刚变成0的节点进入下一波。
        throw new UnsupportedOperationException("TODO 5：释放下一波依赖");
    }

    static void verifyFinished(int finished, int total) {
        // TODO 6：不等时抛IllegalStateException，消息包含cycle；相等时正常返回。
        throw new UnsupportedOperationException("TODO 6：检测未完成节点或环");
    }

    // 以下是检查脚手架，不是算法答案。
    public static void main(String[] args) {
        normalCase();
        failureCase();
        cycleCase();
        System.out.println("PASS practice：正常拓扑与同波并发、节点失败、环，共3项检查通过。");
    }

    static List<Edge> goodsEdges() {
        return List.of(new Edge("content", "goods"), new Edge("content", "price"),
                new Edge("goods", "assemble"), new Edge("price", "assemble"));
    }

    static void normalCase() {
        Map<String, Long> start = new ConcurrentHashMap<>();
        Map<String, Long> end = new ConcurrentHashMap<>();
        Map<String, Integer> counts = new ConcurrentHashMap<>();
        Map<String, String> results = new ConcurrentHashMap<>();
        CountDownLatch middleBothStarted = new CountDownLatch(2);
        Map<String, Runnable> tasks = new LinkedHashMap<>();
        for (String id : List.of("content", "goods", "price", "assemble")) {
            tasks.put(id, () -> {
                counts.merge(id, 1, Integer::sum);
                start.put(id, System.nanoTime());
                if (id.equals("goods") || id.equals("price")) {
                    middleBothStarted.countDown();
                    await(middleBothStarted); // 顺序提交并join会卡住并报错。
                }
                if (id.equals("assemble")) {
                    check(results.containsKey("goods") && results.containsKey("price"), "缺少聚合依赖");
                }
                results.put(id, id + "-ok");
                end.put(id, System.nanoTime());
            });
        }
        try (ExecutorService nodePool = Executors.newFixedThreadPool(2)) {
            run(tasks, goodsEdges(), nodePool);
        }
        check(results.size() == 4 && counts.size() == 4, "四个节点都要执行");
        check(counts.values().stream().allMatch(n -> n == 1), "每个节点只能执行一次");
        for (Edge e : goodsEdges()) {
            check(end.containsKey(e.from()) && start.containsKey(e.to()), "边两端必须执行");
            check(end.get(e.from()) <= start.get(e.to()), "依赖顺序错误：" + e);
        }
        check("assemble-ok".equals(results.get("assemble")), "缺少输出结果");
        System.out.println("通过1：正常拓扑、同波并发和输出结果");
    }

    static void failureCase() {
        Set<String> ran = ConcurrentHashMap.newKeySet();
        Map<String, Runnable> tasks = new LinkedHashMap<>();
        for (String id : List.of("content", "goods", "price", "assemble")) {
            tasks.put(id, () -> {
                ran.add(id);
                if (id.equals("price")) throw new IllegalStateException("price-failed");
            });
        }
        boolean propagated = false;
        try (ExecutorService nodePool = Executors.newFixedThreadPool(2)) {
            try { run(tasks, goodsEdges(), nodePool); }
            catch (RuntimeException ex) {
                Throwable cause = ex;
                while (cause.getCause() != null) cause = cause.getCause();
                check("price-failed".equals(cause.getMessage()), "必须传播真实price失败，实际：" + cause);
                propagated = true;
            }
        }
        check(propagated && !ran.contains("assemble"), "失败应传播且不推进下一波");
        System.out.println("通过2：节点失败不进入聚合波");
    }

    static void cycleCase() {
        Runnable never = () -> { throw new AssertionError("环中的节点不应执行"); };
        boolean detected = false;
        try (ExecutorService nodePool = Executors.newFixedThreadPool(2)) {
            try { run(Map.of("a", never, "b", never), List.of(new Edge("a", "b"), new Edge("b", "a")), nodePool); }
            catch (IllegalStateException ex) {
                check(ex.getMessage() != null && ex.getMessage().contains("cycle"), "环错误消息应含cycle");
                detected = true;
            }
        }
        check(detected, "必须检测到环");
        System.out.println("通过3：环检测");
    }

    static void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("同波任务未并发启动，请检查是否过早join");
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new CompletionException(ex); }
    }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
