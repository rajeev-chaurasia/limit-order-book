package io.github.rajeevchaurasia.orderbook.app.loadtest;

import io.github.rajeevchaurasia.orderbook.book.Side;
import io.github.rajeevchaurasia.orderbook.engine.EngineLoop;
import io.github.rajeevchaurasia.orderbook.engine.WaitStrategy;
import io.github.rajeevchaurasia.orderbook.gateway.EngineGateway;
import io.github.rajeevchaurasia.orderbook.ring.MpscCommandRing;
import org.HdrHistogram.Histogram;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.locks.LockSupport;

/**
 * End-to-end system measurement: producer threads submit orders through the
 * gateway at a fixed schedule and record enqueue-to-ack latency.
 *
 * <p>Latency is measured against each order's intended start time on the
 * fixed schedule, not against when the producer got around to sending it.
 * A slow response therefore penalizes every order queued behind it, which
 * is the coordinated-omission-corrected number; measuring only send-to-ack
 * would hide exactly the stalls a latency report exists to show.
 *
 * <p>Usage: LoadTestMain [ordersPerSecond] [durationSeconds] [producers] [hgrmFile]
 */
public final class LoadTestMain {

    private static final int RING_CAPACITY = 1 << 16;
    private static final int PREFILL_DEPTH = 256;
    private static final int PREFILL_ORDERS_PER_LEVEL = 4;
    private static final long MID_PRICE = 10_000;
    private static final long TICK = 5;

    private static final double WARMUP_FRACTION = 0.2;
    private static final long HISTOGRAM_MAX_NANOS = 10_000_000_000L;
    private static final int HISTOGRAM_DIGITS = 3;

    private static final double CROSS_PROBABILITY = 0.10;
    private static final double CANCEL_PROBABILITY = 0.40;

    private LoadTestMain() {
    }

    public static void main(String[] args) throws Exception {
        int ordersPerSecond = args.length > 0 ? Integer.parseInt(args[0]) : 100_000;
        int durationSeconds = args.length > 1 ? Integer.parseInt(args[1]) : 30;
        int producers = args.length > 2 ? Integer.parseInt(args[2]) : 2;
        Path hgrmFile = Path.of(args.length > 3 ? args[3] : "build/e2e-latency.hgrm");

        MpscCommandRing ring = new MpscCommandRing(RING_CAPACITY);
        EngineLoop loop = new EngineLoop(ring, WaitStrategy.BUSY_SPIN);
        loop.start();
        EngineGateway gateway = new EngineGateway(ring, loop);
        prefill(gateway);

        int perProducerRate = ordersPerSecond / producers;
        long ordersPerProducer = (long) perProducerRate * durationSeconds;
        long warmupOrders = (long) (ordersPerProducer * WARMUP_FRACTION);

        System.out.printf("Load test: %,d orders/s, %d s, %d producers (%,d orders each, first %,d warmup)%n",
                ordersPerSecond, durationSeconds, producers, ordersPerProducer, warmupOrders);

        List<Producer> workers = new ArrayList<>(producers);
        List<Thread> threads = new ArrayList<>(producers);
        for (int p = 0; p < producers; p++) {
            Producer producer = new Producer(
                    gateway, p, perProducerRate, ordersPerProducer, warmupOrders);
            workers.add(producer);
            Thread thread = new Thread(producer, "load-producer-" + p);
            threads.add(thread);
        }
        long start = System.nanoTime();
        threads.forEach(Thread::start);
        for (Thread thread : threads) {
            thread.join();
        }
        long elapsed = System.nanoTime() - start;
        loop.stop();

        Histogram merged = new Histogram(HISTOGRAM_MAX_NANOS, HISTOGRAM_DIGITS);
        for (Producer producer : workers) {
            merged.add(producer.histogram);
        }

        double achieved = (double) ordersPerProducer * producers / (elapsed / 1e9);
        System.out.printf("Achieved: %,.0f orders/s over %.1f s%n", achieved, elapsed / 1e9);
        System.out.printf("Latency (us): p50=%.1f p90=%.1f p99=%.1f p99.9=%.1f p99.99=%.1f max=%.1f%n",
                merged.getValueAtPercentile(50) / 1e3,
                merged.getValueAtPercentile(90) / 1e3,
                merged.getValueAtPercentile(99) / 1e3,
                merged.getValueAtPercentile(99.9) / 1e3,
                merged.getValueAtPercentile(99.99) / 1e3,
                merged.getMaxValue() / 1e3);

        Files.createDirectories(hgrmFile.toAbsolutePath().getParent());
        try (PrintStream out = new PrintStream(Files.newOutputStream(hgrmFile))) {
            merged.outputPercentileDistribution(out, 1_000.0);
        }
        System.out.println("Percentile distribution written to " + hgrmFile);
    }

    private static void prefill(EngineGateway gateway) {
        long id = 1;
        for (int levelIndex = 1; levelIndex <= PREFILL_DEPTH; levelIndex++) {
            for (int k = 0; k < PREFILL_ORDERS_PER_LEVEL; k++) {
                gateway.submit(id++, Side.BUY, MID_PRICE - levelIndex * TICK, 50);
                gateway.submit(id++, Side.SELL, MID_PRICE + levelIndex * TICK, 50);
            }
        }
    }

    private static final class Producer implements Runnable {
        final Histogram histogram = new Histogram(HISTOGRAM_MAX_NANOS, HISTOGRAM_DIGITS);

        private final EngineGateway gateway;
        private final long idBase;
        private final long intervalNanos;
        private final long orders;
        private final long warmupOrders;
        private final Random random;
        private final long[] recentIds = new long[1024];
        private int recentCount;

        Producer(EngineGateway gateway, int index, int ratePerSecond, long orders, long warmupOrders) {
            this.gateway = gateway;
            this.idBase = 1_000_000_000L * (index + 1);
            this.intervalNanos = 1_000_000_000L / ratePerSecond;
            this.orders = orders;
            this.warmupOrders = warmupOrders;
            this.random = new Random(7_000 + index);
        }

        @Override
        public void run() {
            long base = System.nanoTime();
            for (long k = 0; k < orders; k++) {
                long intendedStart = base + k * intervalNanos;
                waitUntil(intendedStart);
                issueCommand(k);
                long latency = System.nanoTime() - intendedStart;
                if (k >= warmupOrders) {
                    histogram.recordValue(Math.min(latency, HISTOGRAM_MAX_NANOS));
                }
            }
        }

        private void issueCommand(long k) {
            double roll = random.nextDouble();
            if (roll < CANCEL_PROBABILITY && recentCount > 0) {
                int slot = random.nextInt(Math.min(recentCount, recentIds.length));
                gateway.cancel(recentIds[slot]);
                return;
            }
            long orderId = idBase + k;
            boolean buy = random.nextBoolean();
            long price;
            if (roll >= 1.0 - CROSS_PROBABILITY) {
                price = buy ? MID_PRICE + PREFILL_DEPTH * TICK : MID_PRICE - PREFILL_DEPTH * TICK;
            } else {
                long offset = (1 + random.nextInt(PREFILL_DEPTH)) * TICK;
                price = buy ? MID_PRICE - offset : MID_PRICE + offset;
            }
            gateway.submit(orderId, buy ? Side.BUY : Side.SELL, price, 10 + random.nextInt(91));
            recentIds[(int) (recentCount++ % recentIds.length)] = orderId;
        }

        private void waitUntil(long deadline) {
            long now;
            while ((now = System.nanoTime()) < deadline) {
                long remaining = deadline - now;
                if (remaining > 100_000) {
                    LockSupport.parkNanos(remaining - 50_000);
                } else {
                    Thread.onSpinWait();
                }
            }
        }
    }
}
