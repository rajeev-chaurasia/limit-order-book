package io.github.rajeevchaurasia.orderbook.gateway;

import io.github.rajeevchaurasia.orderbook.book.Side;
import io.github.rajeevchaurasia.orderbook.engine.CancelResult;
import io.github.rajeevchaurasia.orderbook.engine.EngineLoop;
import io.github.rajeevchaurasia.orderbook.engine.OrderResult;
import io.github.rajeevchaurasia.orderbook.marketdata.BookSnapshot;
import io.github.rajeevchaurasia.orderbook.marketdata.L1Quote;
import io.github.rajeevchaurasia.orderbook.marketdata.StatsSnapshot;
import io.github.rajeevchaurasia.orderbook.marketdata.TradeRecord;
import io.github.rajeevchaurasia.orderbook.ring.CommandType;
import io.github.rajeevchaurasia.orderbook.ring.MpscCommandRing;
import io.github.rajeevchaurasia.orderbook.ring.OrderCommand;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

/**
 * Producer-side entry point to the engine. Any number of threads may call
 * this concurrently; each call claims a ring slot, writes the command, and
 * waits on a response future the engine thread completes.
 *
 * <p>Callers must only block on the returned futures with a timeout and
 * never chain dependents on them: the engine thread runs {@code complete},
 * so a chained dependent would execute on it.
 *
 * <p>Backpressure: a full ring is first spun on briefly, then parked and
 * retried up to a deadline, then surfaced as {@link EngineBusyException}
 * (HTTP 503 at the REST edge).
 */
public final class EngineGateway {

    private static final int CLAIM_SPINS = 1024;
    private static final long CLAIM_PARK_NANOS = 100_000;
    private static final long CLAIM_DEADLINE_NANOS = TimeUnit.MILLISECONDS.toNanos(200);
    private static final long RESPONSE_TIMEOUT_SECONDS = 5;

    private final MpscCommandRing ring;
    private final EngineLoop loop;

    public EngineGateway(MpscCommandRing ring, EngineLoop loop) {
        this.ring = ring;
        this.loop = loop;
    }

    public OrderResult submit(long orderId, Side side, long price, long quantity) {
        CompletableFuture<Object> response = new CompletableFuture<>();
        long ticket = claim();
        OrderCommand command = ring.slot(ticket);
        command.type = CommandType.NEW;
        command.orderId = orderId;
        command.side = side;
        command.price = price;
        command.quantity = quantity;
        command.response = response;
        ring.publish(ticket);
        return (OrderResult) await(response);
    }

    /**
     * Fire-and-forget submit: no response future, no per-call allocation.
     * Results are observable only through market data. Load generators use
     * this path.
     */
    public void submitNoReply(long orderId, Side side, long price, long quantity) {
        long ticket = claim();
        OrderCommand command = ring.slot(ticket);
        command.type = CommandType.NEW;
        command.orderId = orderId;
        command.side = side;
        command.price = price;
        command.quantity = quantity;
        command.response = null;
        ring.publish(ticket);
    }

    public CancelResult cancel(long orderId) {
        CompletableFuture<Object> response = new CompletableFuture<>();
        long ticket = claim();
        OrderCommand command = ring.slot(ticket);
        command.type = CommandType.CANCEL;
        command.orderId = orderId;
        command.side = null;
        command.price = 0;
        command.quantity = 0;
        command.response = response;
        ring.publish(ticket);
        return (CancelResult) await(response);
    }

    @SuppressWarnings("unchecked")
    public List<TradeRecord> recentTrades(int max) {
        return (List<TradeRecord>) request(CommandType.SNAPSHOT_TRADES, max);
    }

    public BookSnapshot book() {
        return (BookSnapshot) request(CommandType.SNAPSHOT_BOOK, 0);
    }

    public StatsSnapshot stats() {
        return (StatsSnapshot) request(CommandType.SNAPSHOT_STATS, 0);
    }

    /** Top of book, read from the seqlock without touching the ring. */
    public L1Quote.Quote quote() {
        return loop.l1Quote().read();
    }

    private Object request(CommandType type, long quantity) {
        CompletableFuture<Object> response = new CompletableFuture<>();
        long ticket = claim();
        OrderCommand command = ring.slot(ticket);
        command.type = type;
        command.orderId = 0;
        command.side = null;
        command.price = 0;
        command.quantity = quantity;
        command.response = response;
        ring.publish(ticket);
        return await(response);
    }

    private long claim() {
        long ticket = ring.tryClaim();
        if (ticket != MpscCommandRing.FULL) {
            return ticket;
        }
        for (int i = 0; i < CLAIM_SPINS; i++) {
            Thread.onSpinWait();
            ticket = ring.tryClaim();
            if (ticket != MpscCommandRing.FULL) {
                return ticket;
            }
        }
        long deadline = System.nanoTime() + CLAIM_DEADLINE_NANOS;
        while (System.nanoTime() < deadline) {
            LockSupport.parkNanos(CLAIM_PARK_NANOS);
            ticket = ring.tryClaim();
            if (ticket != MpscCommandRing.FULL) {
                return ticket;
            }
        }
        throw new EngineBusyException("Command ring full for over "
                + TimeUnit.NANOSECONDS.toMillis(CLAIM_DEADLINE_NANOS) + " ms");
    }

    private Object await(CompletableFuture<Object> response) {
        try {
            return response.get(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for the engine", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Engine command failed", e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("Engine did not answer within "
                    + RESPONSE_TIMEOUT_SECONDS + " s", e);
        }
    }
}
