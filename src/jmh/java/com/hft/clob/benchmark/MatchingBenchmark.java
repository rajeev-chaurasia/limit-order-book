package com.hft.clob.benchmark;

import com.hft.clob.core.*;
import com.hft.clob.engine.*;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JMH Benchmark for order matching (crossing orders).
 * Measures end-to-end latency from order receipt to trade execution.
 * 
 * Target: < 50 microseconds P99 latency
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class MatchingBenchmark {

    private OrderPool pool;
    private OrderBook book;
    private MatchingEngine engine;
    private long buyOrderId;
    private long sellOrderId;

    @Setup(Level.Invocation)
    public void setup() {
        // Fresh book for each iteration
        pool = new OrderPool();
        book = new OrderBook(pool);
        engine = new MatchingEngine(book);

        buyOrderId = 1000000;
        sellOrderId = 2000000;

        // Pre-populate book with resting sell order
        engine.processOrder(sellOrderId++, (byte) 'S', 10500, 100);
    }

    @Benchmark
    public void matchBuyAgainstSell(Blackhole bh) {
        // Incoming buy order that crosses spread
        List<Trade> trades = engine.processOrder(buyOrderId++, (byte) 'B', 10500, 50);
        bh.consume(trades);
    }

    @Benchmark
    public void matchWithPartialFill(Blackhole bh) {
        // Larger buy order (partial fill scenario)
        List<Trade> trades = engine.processOrder(buyOrderId++, (byte) 'B', 10500, 150);
        bh.consume(trades);
    }

    @State(Scope.Thread)
    public static class DepthBookState {
        OrderPool pool;
        OrderBook book;
        MatchingEngine engine;
        long orderId;

        @Setup(Level.Invocation)
        public void setup() {
            pool = new OrderPool();
            book = new OrderBook(pool);
            engine = new MatchingEngine(book);
            orderId = 3000000;

            // Create deep book (10 price levels, 10 orders each)
            for (int priceLevel = 0; priceLevel < 10; priceLevel++) {
                long price = 10500 + (priceLevel * 10);
                for (int i = 0; i < 10; i++) {
                    engine.processOrder(orderId++, (byte) 'S', price, 100);
                }
            }
        }
    }

    @Benchmark
    public void matchAgainstDeepBook(DepthBookState state, Blackhole bh) {
        // Large buy order that sweeps through multiple price levels
        List<Trade> trades = state.engine.processOrder(
                state.orderId++, (byte) 'B', 10600, 500);
        bh.consume(trades);
    }
}
