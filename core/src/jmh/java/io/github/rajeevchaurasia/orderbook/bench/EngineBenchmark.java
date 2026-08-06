package io.github.rajeevchaurasia.orderbook.bench;

import io.github.rajeevchaurasia.orderbook.baseline.NaiveMatchingEngine;
import io.github.rajeevchaurasia.orderbook.bench.workload.CommandStream;
import io.github.rajeevchaurasia.orderbook.book.LevelPool;
import io.github.rajeevchaurasia.orderbook.book.OrderPool;
import io.github.rajeevchaurasia.orderbook.book.Side;
import io.github.rajeevchaurasia.orderbook.engine.EngineListener;
import io.github.rajeevchaurasia.orderbook.engine.MatchingEngine;
import io.github.rajeevchaurasia.orderbook.engine.OrderBookEngine;
import io.github.rajeevchaurasia.orderbook.engine.RejectReason;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Mechanism benchmark: one thread drives one engine implementation through
 * a precomputed command stream. This isolates the cost of matching, book
 * maintenance, and allocation behavior; the ring handoff and end-to-end
 * system numbers are measured separately.
 *
 * <p>Run in Throughput mode for ops/s and SampleTime mode for latency
 * percentiles, both with the GC profiler. The optimized engine must report
 * approximately 0 B/op; the naive engine's allocation rate is the contrast.
 *
 * <p>The stream index cycles; ids derive from an absolute counter so laps
 * never collide. The book is rebuilt and prefilled every iteration, outside
 * the measured window.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(3)
public class EngineBenchmark {

    private static final int STREAM_SIZE = 1 << 20;
    private static final long STREAM_SEED = 42;
    private static final int BENCH_ORDER_POOL = 1 << 18;
    private static final int BENCH_LEVEL_POOL = 1 << 14;

    /** Consumes every event into a checksum the benchmark returns. */
    static final class SinkListener implements EngineListener {
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

    @Param({"optimized", "naive"})
    public String engine;

    @Param({"16", "256", "2048"})
    public int depth;

    @Param({"QUIET", "ACTIVE", "CROSS_HEAVY"})
    public String mix;

    private CommandStream stream;
    private OrderBookEngine target;
    private SinkListener listener;

    private int index;
    private long absolute;

    @Setup(Level.Trial)
    public void generateStream() {
        stream = CommandStream.generate(STREAM_SEED, depth, CommandStream.Mix.valueOf(mix), STREAM_SIZE);
    }

    @Setup(Level.Iteration)
    public void rebuildEngine() {
        listener = new SinkListener();
        // A counter clock for both engines: isolates matching cost from
        // System.nanoTime, and keeps the comparison symmetric.
        long[] tick = {0};
        if ("optimized".equals(engine)) {
            target = new MatchingEngine(
                    new OrderPool(BENCH_ORDER_POOL), new LevelPool(BENCH_LEVEL_POOL), listener, () -> ++tick[0]);
        } else {
            target = new NaiveMatchingEngine(listener, () -> ++tick[0]);
        }
        index = 0;
        absolute = 0;
        prefill();
    }

    private void prefill() {
        long id = 1;
        for (int levelIndex = 1; levelIndex <= depth; levelIndex++) {
            long bid = CommandStream.MID_PRICE - levelIndex * CommandStream.TICK;
            long ask = CommandStream.MID_PRICE + levelIndex * CommandStream.TICK;
            for (int k = 0; k < CommandStream.PREFILL_ORDERS_PER_LEVEL; k++) {
                target.submit(id++, Side.BUY, bid, CommandStream.PREFILL_QUANTITY);
                target.submit(id++, Side.SELL, ask, CommandStream.PREFILL_QUANTITY);
            }
        }
    }

    @Benchmark
    public long processCommand() {
        int i = index;
        long commandId = CommandStream.ID_BASE + absolute;
        switch (stream.actions[i]) {
            case CommandStream.ACTION_CANCEL ->
                    target.cancel(commandId - stream.cancelDistances[i]);
            default ->
                    target.submit(commandId, Side.fromCode(stream.sideCodes[i]),
                            stream.prices[i], stream.quantities[i]);
        }
        absolute++;
        index = i + 1 == stream.size ? 0 : i + 1;
        return listener.checksum;
    }
}
