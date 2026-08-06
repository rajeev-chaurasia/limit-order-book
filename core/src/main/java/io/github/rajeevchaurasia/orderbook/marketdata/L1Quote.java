package io.github.rajeevchaurasia.orderbook.marketdata;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Top-of-book quote published through a seqlock: the single writer (the
 * engine thread) is wait-free and allocation-free, and any number of reader
 * threads read without locking or blocking the writer.
 *
 * <p>Protocol: the writer bumps the version to odd, writes the fields, then
 * bumps it to even. A reader takes the version, reads the fields, and
 * re-checks the version; a torn read shows up as an odd or changed version
 * and the reader retries. The store-store fences keep the field writes
 * strictly between the two version stores, and the load-load fences keep the
 * field reads strictly between the two version reads. Plain volatile
 * release/acquire is not enough here: a release store only orders earlier
 * writes, so the field writes could float above the odd version store and a
 * reader could validate a torn read.
 *
 * <p>A price of 0 ({@code OrderBookEngine.NO_PRICE}) marks an empty side.
 */
public final class L1Quote {

    private static final VarHandle VERSION;

    static {
        try {
            VERSION = MethodHandles.lookup().findVarHandle(L1Quote.class, "version", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings("unused")
    private volatile long version;

    private long bestBid;
    private long bestBidQuantity;
    private long bestAsk;
    private long bestAskQuantity;

    /** Consistent view of the top of book. */
    public record Quote(long bestBid, long bestBidQuantity, long bestAsk, long bestAskQuantity) {
    }

    /** Single writer only. */
    public void publish(long bid, long bidQuantity, long ask, long askQuantity) {
        long v = (long) VERSION.getOpaque(this);
        VERSION.setOpaque(this, v + 1);
        VarHandle.storeStoreFence();
        bestBid = bid;
        bestBidQuantity = bidQuantity;
        bestAsk = ask;
        bestAskQuantity = askQuantity;
        VarHandle.storeStoreFence();
        VERSION.setOpaque(this, v + 2);
    }

    /** Any thread. Spins only while the writer is mid-update. */
    public Quote read() {
        for (;;) {
            long v1 = (long) VERSION.getOpaque(this);
            VarHandle.loadLoadFence();
            long bid = bestBid;
            long bidQuantity = bestBidQuantity;
            long ask = bestAsk;
            long askQuantity = bestAskQuantity;
            VarHandle.loadLoadFence();
            long v2 = (long) VERSION.getOpaque(this);
            if (v1 == v2 && (v1 & 1) == 0) {
                return new Quote(bid, bidQuantity, ask, askQuantity);
            }
            Thread.onSpinWait();
        }
    }
}
