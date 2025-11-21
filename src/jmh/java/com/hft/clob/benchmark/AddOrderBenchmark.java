package com.hft.clob.benchmark;

import com.hft.clob.core.*;
import com.hft.clob.engine.*;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JMH Benchmark for AddOrder operation.
 * Measures throughput (ops/sec) and latency (ns/op) with GC profiling.
 * 
 * Target: > 100,000 ops/sec with 0 bytes/op allocation in steady state.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class AddOrderBenchmark {

    private OrderPool pool;
    private OrderBook book;
    private MatchingEngine engine;
    private AtomicLong orderIdGenerator;

    @Setup(Level.Trial)
    public void setup() {
        pool = new OrderPool();
        book = new OrderBook(pool);
        engine = new MatchingEngine(book);
        orderIdGenerator = new AtomicLong(1);
    }

    @Benchmark
    public void addBuyOrder(Blackhole bh) {
        long orderId = orderIdGenerator.getAndIncrement();
        long price = 10000 + (orderId % 100); // Vary price slightly to avoid collisions
        List<Trade> trades = engine.processOrder(orderId, (byte) 'B', price, 100);
        bh.consume(trades);
    }

    @Benchmark
    public void addSellOrder(Blackhole bh) {
        long orderId = orderIdGenerator.getAndIncrement();
        long price = 10100 + (orderId % 100); // Higher than buys to avoid matching
        List<Trade> trades = engine.processOrder(orderId, (byte) 'S', price, 100);
        bh.consume(trades);
    }

    @Benchmark
    public void addAlternatingOrders(Blackhole bh) {
        long orderId = orderIdGenerator.getAndIncrement();
        byte side = (orderId % 2 == 0) ? (byte) 'B' : (byte) 'S';
        long price = (side == 'B') ? 10000 : 10100;

        List<Trade> trades = engine.processOrder(orderId, side, price, 100);
        bh.consume(trades);
    }
}
