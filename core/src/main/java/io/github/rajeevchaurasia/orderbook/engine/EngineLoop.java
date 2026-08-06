package io.github.rajeevchaurasia.orderbook.engine;

import io.github.rajeevchaurasia.orderbook.book.OrderLevel;
import io.github.rajeevchaurasia.orderbook.book.Side;
import io.github.rajeevchaurasia.orderbook.marketdata.L1Quote;
import io.github.rajeevchaurasia.orderbook.marketdata.TradeTape;
import io.github.rajeevchaurasia.orderbook.ring.CommandType;
import io.github.rajeevchaurasia.orderbook.ring.MpscCommandRing;
import io.github.rajeevchaurasia.orderbook.ring.OrderCommand;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The single writer. Owns the matching engine, drains the command ring on a
 * dedicated thread, publishes L1 through the seqlock after every data-plane
 * command, records trades on the tape, and completes response futures.
 *
 * <p>Everything the engine mutates is confined to this thread; the ring, the
 * seqlock, and the response futures are the only points where other threads
 * meet it.
 */
public final class EngineLoop implements Runnable, EngineListener, MpscCommandRing.CommandHandler {

    private static final int DRAIN_BATCH = 256;
    private static final long JOIN_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(5);

    private final MpscCommandRing ring;
    private final MatchingEngine engine;
    private final TradeTape tradeTape = new TradeTape();
    private final L1Quote l1Quote = new L1Quote();
    private final WaitStrategy waitStrategy;

    private volatile boolean running;
    private Thread thread;

    // Per-command scratch. Engine thread only; valid between resetScratch()
    // and the completion of the current command's response.
    private long filled;
    private long resting;
    private int trades;
    private boolean rejected;
    private RejectReason reason;
    private long cancelRemaining;

    public EngineLoop(MpscCommandRing ring, WaitStrategy waitStrategy) {
        this.ring = ring;
        this.waitStrategy = waitStrategy;
        this.engine = new MatchingEngine(this);
    }

    public void start() {
        if (thread != null) {
            throw new IllegalStateException("Engine loop already started");
        }
        running = true;
        thread = new Thread(this, "matching-engine");
        thread.start();
    }

    /**
     * Stops the loop after the commands already in the ring are drained.
     * The SHUTDOWN command goes through the same ring as everything else,
     * so no command published before it is lost.
     */
    public void stop() throws InterruptedException {
        if (thread == null) {
            return;
        }
        long ticket;
        while ((ticket = ring.tryClaim()) == MpscCommandRing.FULL) {
            if (!running) {
                return;
            }
            Thread.onSpinWait();
        }
        OrderCommand command = ring.slot(ticket);
        command.type = CommandType.SHUTDOWN;
        command.response = null;
        ring.publish(ticket);
        thread.join(JOIN_TIMEOUT_MILLIS);
        if (thread.isAlive()) {
            throw new IllegalStateException("Engine thread did not stop within "
                    + JOIN_TIMEOUT_MILLIS + " ms");
        }
    }

    @Override
    public void run() {
        int idleCount = 0;
        while (running) {
            int processed = ring.drain(this, DRAIN_BATCH);
            if (processed == 0) {
                waitStrategy.idle(idleCount++);
            } else {
                idleCount = 0;
            }
        }
    }

    @Override
    public void onCommand(OrderCommand command) {
        CompletableFuture<Object> response = command.response;
        try {
            switch (command.type) {
                case NEW -> {
                    resetScratch();
                    engine.submit(command.orderId, command.side, command.price, command.quantity);
                    publishL1();
                    if (response != null) {
                        response.complete(rejected
                                ? new OrderResult(command.orderId, false, reason, 0, 0, 0)
                                : new OrderResult(command.orderId, true, null, filled, resting, trades));
                    }
                }
                case CANCEL -> {
                    resetScratch();
                    engine.cancel(command.orderId);
                    publishL1();
                    if (response != null) {
                        response.complete(new CancelResult(!rejected, cancelRemaining));
                    }
                }
                case SNAPSHOT_BOOK -> complete(response, engine.snapshotBook());
                case SNAPSHOT_TRADES -> complete(response, tradeTape.recent((int) command.quantity));
                case SNAPSHOT_STATS -> complete(response, engine.snapshotStats());
                case SHUTDOWN -> {
                    running = false;
                    if (response != null) {
                        response.complete(Boolean.TRUE);
                    }
                }
            }
        } catch (RuntimeException e) {
            // A command must never kill the engine thread; surface the
            // failure to the caller instead.
            if (response != null && !response.isDone()) {
                response.completeExceptionally(e);
            }
        }
    }

    private static void complete(CompletableFuture<Object> response, Object value) {
        if (response != null) {
            response.complete(value);
        }
    }

    private void publishL1() {
        OrderLevel bestBid = engine.book().bestLevel(Side.BUY);
        OrderLevel bestAsk = engine.book().bestLevel(Side.SELL);
        l1Quote.publish(
                bestBid == null ? OrderBookEngine.NO_PRICE : bestBid.price(),
                bestBid == null ? 0 : bestBid.totalQuantity(),
                bestAsk == null ? OrderBookEngine.NO_PRICE : bestAsk.price(),
                bestAsk == null ? 0 : bestAsk.totalQuantity());
    }

    private void resetScratch() {
        filled = 0;
        resting = 0;
        trades = 0;
        rejected = false;
        reason = null;
        cancelRemaining = 0;
    }

    @Override
    public void onTrade(long buyOrderId, long sellOrderId, long price, long quantity, long timestamp) {
        trades++;
        tradeTape.record(buyOrderId, sellOrderId, price, quantity, timestamp);
    }

    @Override
    public void onOrderAccepted(long orderId, long filledQuantity, long restingQuantity) {
        filled = filledQuantity;
        resting = restingQuantity;
    }

    @Override
    public void onOrderCanceled(long orderId, long remainingQuantity) {
        cancelRemaining = remainingQuantity;
    }

    @Override
    public void onOrderRejected(long orderId, RejectReason rejectReason) {
        rejected = true;
        reason = rejectReason;
    }

    public L1Quote l1Quote() {
        return l1Quote;
    }

    public boolean isRunning() {
        return running;
    }
}
