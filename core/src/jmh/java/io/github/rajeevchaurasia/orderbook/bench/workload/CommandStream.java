package io.github.rajeevchaurasia.orderbook.bench.workload;

import io.github.rajeevchaurasia.orderbook.book.Side;
import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.util.Random;

/**
 * Precomputed, seeded command stream for the engine benchmarks.
 *
 * <p>Design goals: no generation work or allocation inside the measured
 * loop, and a roughly stationary book. Adds and cancels are generated
 * against a simulated live-order list so every cancel targets an order that
 * was added earlier in the stream; crossing orders drain liquidity that
 * subsequent adds replenish. Cancels of orders that a cross already filled
 * surface as cheap UNKNOWN_ORDER rejects, which is realistic order flow.
 *
 * <p>The stream can be replayed for any number of laps: order ids are
 * derived from an absolute command counter, and each cancel stores the
 * fixed distance back to the add it targets, so ids stay consistent across
 * laps.
 */
public final class CommandStream {

    public enum Mix {
        /** Mostly add/cancel churn, 2 percent marketable orders. */
        QUIET(0.02),
        /** 10 percent marketable orders. */
        ACTIVE(0.10),
        /** 30 percent marketable orders. */
        CROSS_HEAVY(0.30);

        final double crossProbability;

        Mix(double crossProbability) {
            this.crossProbability = crossProbability;
        }
    }

    public static final byte ACTION_ADD = 0;
    public static final byte ACTION_CANCEL = 1;
    public static final byte ACTION_CROSS = 2;

    /** Ids for stream commands start here; prefill ids stay far below. */
    public static final long ID_BASE = 1_000_000_000L;

    public static final long MID_PRICE = 10_000;
    public static final long TICK = 5;
    public static final int PREFILL_ORDERS_PER_LEVEL = 4;
    public static final long PREFILL_QUANTITY = 50;

    private static final long MIN_QUANTITY = 10;
    private static final long QUANTITY_RANGE = 90;

    /**
     * Marketable orders reach this many ticks past the mid, sweeping only
     * the top of the far side, and carry add-sized quantities. Deep sweeps
     * with large quantities drain liquidity faster than the adds replenish
     * it, and the book degenerates instead of staying stationary.
     */
    private static final long CROSS_TICKS_PAST_MID = 8;

    public final byte[] actions;
    public final byte[] sideCodes;
    public final long[] prices;
    public final long[] quantities;
    /** For cancels: distance from this command back to the targeted add. */
    public final long[] cancelDistances;

    public final int size;
    public final int depth;

    private CommandStream(int size, int depth) {
        this.size = size;
        this.depth = depth;
        this.actions = new byte[size];
        this.sideCodes = new byte[size];
        this.prices = new long[size];
        this.quantities = new long[size];
        this.cancelDistances = new long[size];
    }

    public static CommandStream generate(long seed, int depth, Mix mix, int size) {
        CommandStream stream = new CommandStream(size, depth);
        Random random = new Random(seed);
        IntArrayList live = new IntArrayList(size / 2);

        double crossP = mix.crossProbability;
        double cancelP = (1.0 - crossP) / 2.0;

        for (int i = 0; i < size; i++) {
            double roll = random.nextDouble();
            if (roll < crossP) {
                fillCross(stream, i, random, depth);
            } else if (roll < crossP + cancelP && !live.isEmpty()) {
                int position = random.nextInt(live.size());
                int target = live.getInt(position);
                live.set(position, live.getInt(live.size() - 1));
                live.removeInt(live.size() - 1);
                stream.actions[i] = ACTION_CANCEL;
                stream.cancelDistances[i] = i - target;
            } else {
                fillAdd(stream, i, random, depth);
                live.add(i);
            }
        }
        return stream;
    }

    private static void fillAdd(CommandStream stream, int i, Random random, int depth) {
        boolean buy = random.nextBoolean();
        stream.actions[i] = ACTION_ADD;
        stream.sideCodes[i] = buy ? Side.BUY.code : Side.SELL.code;
        long offset = (1 + random.nextInt(depth)) * TICK;
        stream.prices[i] = buy ? MID_PRICE - offset : MID_PRICE + offset;
        stream.quantities[i] = MIN_QUANTITY + random.nextLong(QUANTITY_RANGE + 1);
    }

    private static void fillCross(CommandStream stream, int i, Random random, int depth) {
        boolean buy = random.nextBoolean();
        long reach = Math.min(CROSS_TICKS_PAST_MID, depth) * TICK;
        stream.actions[i] = ACTION_CROSS;
        stream.sideCodes[i] = buy ? Side.BUY.code : Side.SELL.code;
        stream.prices[i] = buy ? MID_PRICE + reach : MID_PRICE - reach;
        stream.quantities[i] = MIN_QUANTITY + random.nextLong(QUANTITY_RANGE + 1);
    }
}
