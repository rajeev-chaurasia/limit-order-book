package io.github.rajeevchaurasia.orderbook.bench;

import io.github.rajeevchaurasia.orderbook.book.LevelPool;
import io.github.rajeevchaurasia.orderbook.book.OrderPool;
import io.github.rajeevchaurasia.orderbook.book.Side;
import io.github.rajeevchaurasia.orderbook.engine.EngineListener;
import io.github.rajeevchaurasia.orderbook.engine.MatchingEngine;
import io.github.rajeevchaurasia.orderbook.engine.RejectReason;

import java.lang.management.ManagementFactory;

/**
 * Direct allocation measurement of the engine's data plane, bypassing any
 * harness noise: drives the engine through each operation shape and reports
 * exact bytes allocated by this thread via ThreadMXBean.
 *
 * <p>This is the arbiter behind the zero-allocation claim. The JMH GC
 * profiler reports a small nonzero B/op on short runs because it counts the
 * whole process (JIT compiler threads included); this harness counts only
 * the engine thread, and the engine must come out at exactly 0.000 B/op on
 * every data-plane path.
 *
 * <p>Run it from the JMH jar:
 * java -cp core/build/libs/core-2.0.0-jmh.jar
 *     io.github.rajeevchaurasia.orderbook.bench.AllocationCheck
 *
 * <p>For the endurance variant, add: -XX:+UnlockExperimentalVMOptions
 * -XX:+UseEpsilonGC -Xmx256m. Epsilon never collects, so surviving the run
 * is itself the proof that the data plane does not allocate.
 */
public final class AllocationCheck {

    private static final int OPERATIONS = 10_000_000;

    static final class Sink implements EngineListener {
        long checksum;

        @Override
        public void onTrade(long buyOrderId, long sellOrderId, long price, long quantity, long timestamp) {
            checksum += buyOrderId ^ sellOrderId ^ price ^ quantity;
        }

        @Override
        public void onOrderAccepted(long orderId, long filledQuantity, long restingQuantity) {
            checksum += orderId + restingQuantity;
        }

        @Override
        public void onOrderCanceled(long orderId, long remainingQuantity) {
            checksum += orderId - remainingQuantity;
        }

        @Override
        public void onOrderRejected(long orderId, RejectReason reason) {
            checksum += orderId;
        }
    }

    private AllocationCheck() {
    }

    public static void main(String[] args) {
        boolean epsilon = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .anyMatch(gc -> gc.getName().contains("Epsilon"));
        System.out.println("Engine data-plane allocation check ("
                + OPERATIONS + " ops per scenario" + (epsilon ? ", Epsilon GC endurance mode" : "") + ")");

        long failures = 0;
        failures += runScenario("add + cancel, one level", AllocationCheck::addCancelSameLevel);
        failures += runScenario("add + cancel, level churn", AllocationCheck::addCancelLevelChurn);
        failures += runScenario("crossing pairs (trades)", AllocationCheck::crossingPairs);
        failures += runScenario("unknown-order rejects", AllocationCheck::unknownCancels);

        if (failures > 0) {
            System.out.println("FAIL: data plane allocated");
            System.exit(1);
        }
        System.out.println("PASS: 0 bytes allocated on every data-plane path");
    }

    private interface Scenario {
        void run(MatchingEngine engine, long idBase);
    }

    private static long runScenario(String label, Scenario scenario) {
        Sink sink = new Sink();
        long[] tick = {0};
        MatchingEngine engine = new MatchingEngine(
                new OrderPool(1 << 18), new LevelPool(1 << 14), sink, () -> ++tick[0]);

        // Warm lap: JIT compilation and pool steady state settle here.
        scenario.run(engine, 1_000_000_000L);

        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long threadId = Thread.currentThread().threadId();
        long before = threads.getThreadAllocatedBytes(threadId);
        scenario.run(engine, 2_000_000_000L);
        long allocated = threads.getThreadAllocatedBytes(threadId) - before;

        System.out.printf("  %-28s %10d bytes (%.4f B/op), checksum %d%n",
                label, allocated, allocated / (double) OPERATIONS, sink.checksum);
        return allocated == 0 ? 0 : 1;
    }

    private static void addCancelSameLevel(MatchingEngine engine, long idBase) {
        for (int i = 0; i < OPERATIONS / 2; i++) {
            long id = idBase + i;
            engine.submit(id, Side.BUY, 100, 10);
            engine.cancel(id);
        }
    }

    private static void addCancelLevelChurn(MatchingEngine engine, long idBase) {
        for (int i = 0; i < OPERATIONS / 2; i++) {
            long id = idBase + i;
            engine.submit(id, Side.BUY, 100 + (i % 512), 10);
            engine.cancel(id);
        }
    }

    private static void crossingPairs(MatchingEngine engine, long idBase) {
        for (int i = 0; i < OPERATIONS / 2; i++) {
            long id = idBase + 2L * i;
            engine.submit(id, Side.SELL, 100, 10);
            engine.submit(id + 1, Side.BUY, 100, 10);
        }
    }

    private static void unknownCancels(MatchingEngine engine, long idBase) {
        for (int i = 0; i < OPERATIONS; i++) {
            engine.cancel(idBase + i);
        }
    }
}
