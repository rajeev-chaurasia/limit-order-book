package io.github.rajeevchaurasia.orderbook.bench;

import io.github.rajeevchaurasia.orderbook.book.Side;
import io.github.rajeevchaurasia.orderbook.ring.CommandType;
import io.github.rajeevchaurasia.orderbook.ring.MpscCommandRing;
import io.github.rajeevchaurasia.orderbook.ring.OrderCommand;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Ring handoff in isolation: three producers claim, fill, and publish
 * command slots while one consumer drains them. The quoted number is the
 * produce throughput. Override the thread split on the command line with
 * -tg when a different producer count is wanted.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Group)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(3)
public class RingBenchmark {

    private static final int RING_CAPACITY = 1 << 14;
    private static final int DRAIN_BATCH = 256;

    private MpscCommandRing ring;
    private ConsumeHandler handler;

    static final class ConsumeHandler implements MpscCommandRing.CommandHandler {
        long checksum;

        @Override
        public void onCommand(OrderCommand command) {
            checksum += command.orderId;
        }
    }

    @Setup(Level.Iteration)
    public void setUp() {
        ring = new MpscCommandRing(RING_CAPACITY);
        handler = new ConsumeHandler();
    }

    @Benchmark
    @Group("handoff")
    @GroupThreads(3)
    public void produce() {
        long ticket;
        while ((ticket = ring.tryClaim()) == MpscCommandRing.FULL) {
            Thread.onSpinWait();
        }
        OrderCommand command = ring.slot(ticket);
        command.type = CommandType.NEW;
        command.orderId = ticket;
        command.side = Side.BUY;
        command.price = 10_000;
        command.quantity = 1;
        command.response = null;
        ring.publish(ticket);
    }

    @Benchmark
    @Group("handoff")
    @GroupThreads(1)
    public long consume() {
        ring.drain(handler, DRAIN_BATCH);
        return handler.checksum;
    }
}
