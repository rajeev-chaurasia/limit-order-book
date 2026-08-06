package io.github.rajeevchaurasia.orderbook.engine;

import io.github.rajeevchaurasia.orderbook.baseline.NaiveMatchingEngine;
import io.github.rajeevchaurasia.orderbook.book.LevelPool;
import io.github.rajeevchaurasia.orderbook.book.OrderPool;
import io.github.rajeevchaurasia.orderbook.book.Side;
import io.github.rajeevchaurasia.orderbook.marketdata.StatsSnapshot;
import io.github.rajeevchaurasia.orderbook.support.RecordingListener;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Differential test: feeds the identical pseudo-random command stream to the
 * optimized engine and the naive baseline, then requires bit-identical event
 * sequences, book snapshots, and stats. Any divergence is a bug in one of the
 * two implementations.
 */
class DifferentialEngineTest {

    private static final int COMMAND_COUNT = 150_000;
    private static final int INVARIANT_CHECK_INTERVAL = 1_000;

    private static final int TYPE_SUBMIT = 0;
    private static final int TYPE_CANCEL = 1;

    private record Command(int type, long orderId, Side side, long price, long quantity) {
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1L, 42L, 20_260_805L})
    void enginesEmitIdenticalResults(long seed) {
        List<Command> commands = generate(seed);

        RecordingListener optimizedListener = new RecordingListener();
        RecordingListener naiveListener = new RecordingListener();
        AtomicLong optimizedClock = new AtomicLong();
        AtomicLong naiveClock = new AtomicLong();
        MatchingEngine optimized = new MatchingEngine(
                new OrderPool(), new LevelPool(), optimizedListener, optimizedClock::incrementAndGet);
        NaiveMatchingEngine naive = new NaiveMatchingEngine(naiveListener, naiveClock::incrementAndGet);

        int executed = 0;
        for (Command command : commands) {
            if (command.type() == TYPE_SUBMIT) {
                optimized.submit(command.orderId(), command.side(), command.price(), command.quantity());
                naive.submit(command.orderId(), command.side(), command.price(), command.quantity());
            } else {
                optimized.cancel(command.orderId());
                naive.cancel(command.orderId());
            }
            executed++;
            if (executed % INVARIANT_CHECK_INTERVAL == 0) {
                BookInvariants.check(optimized);
            }
        }

        String context = "seed=" + seed;
        assertEquals(naiveListener.events(), optimizedListener.events(), context);
        assertEquals(naive.snapshotBook(), optimized.snapshotBook(), context);

        StatsSnapshot naiveStats = naive.snapshotStats();
        StatsSnapshot optimizedStats = optimized.snapshotStats();
        assertEquals(naiveStats.activeOrders(), optimizedStats.activeOrders(), context);
        assertEquals(naiveStats.bidLevels(), optimizedStats.bidLevels(), context);
        assertEquals(naiveStats.askLevels(), optimizedStats.askLevels(), context);
        assertEquals(naiveStats.totalTrades(), optimizedStats.totalTrades(), context);
    }

    /**
     * Roughly 70% new orders, 29% cancels of previously issued ids (often
     * already inactive, exercising UNKNOWN_ORDER), 1% invalid submissions.
     * The stream is materialized once so both engines see exactly the same
     * commands.
     */
    private static List<Command> generate(long seed) {
        Random rnd = new Random(seed);
        List<Command> commands = new ArrayList<>(COMMAND_COUNT);
        long nextId = 1;

        for (int i = 0; i < COMMAND_COUNT; i++) {
            int roll = rnd.nextInt(100);
            if (roll < 70 || nextId == 1) {
                commands.add(newOrder(rnd, nextId++));
            } else if (roll < 99) {
                commands.add(new Command(TYPE_CANCEL, randomIssuedId(rnd, nextId), null, 0, 0));
            } else if (rnd.nextBoolean()) {
                // Zero quantity: always rejected with INVALID_QUANTITY.
                Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
                commands.add(new Command(TYPE_SUBMIT, nextId++, side, randomPrice(rnd), 0));
            } else {
                // Resubmit an issued id: DUPLICATE_ORDER_ID while it is still
                // active, otherwise a legitimate id reuse. Both engines must
                // agree either way.
                Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
                commands.add(new Command(TYPE_SUBMIT, randomIssuedId(rnd, nextId), side,
                        randomPrice(rnd), 1 + rnd.nextInt(500)));
            }
        }
        return commands;
    }

    private static Command newOrder(Random rnd, long orderId) {
        Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
        return new Command(TYPE_SUBMIT, orderId, side, randomPrice(rnd), 1 + rnd.nextInt(500));
    }

    private static long randomPrice(Random rnd) {
        return 9_500 + 5L * rnd.nextInt(201);
    }

    private static long randomIssuedId(Random rnd, long nextId) {
        return 1 + rnd.nextInt((int) (nextId - 1));
    }
}
