package io.github.rajeevchaurasia.orderbook.ring;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Bounded multi-producer, single-consumer ring of preallocated
 * {@link OrderCommand} slots, after Vyukov's bounded queue.
 *
 * <p>Protocol: every slot carries a sequence number. A slot at ring position
 * {@code pos} is free for a producer when {@code sequence == pos}, published
 * for the consumer when {@code sequence == pos + 1}, and recycled for the
 * next lap when the consumer stores {@code pos + capacity}.
 *
 * <p>Memory ordering, and why each barrier exists:
 * <ul>
 * <li>Producers claim a position with a CAS on the tail cursor; the CAS
 *     orders competing producers.</li>
 * <li>A producer's {@code setRelease} of the sequence after writing the slot
 *     fields is the publication point: release guarantees the field writes
 *     cannot reorder after it.</li>
 * <li>The consumer's {@code getAcquire} of the sequence pairs with that
 *     release: once the consumer observes {@code pos + 1}, all field writes
 *     are visible.</li>
 * <li>The consumer's {@code setRelease} of {@code pos + capacity} pairs with
 *     the producer-side {@code getAcquire}, so a producer reusing the slot
 *     cannot overwrite fields the consumer has not finished reading.</li>
 * </ul>
 *
 * <p>The head and tail cursors live in padded holders so the producers'
 * contended tail line is never invalidated by consumer progress on head
 * (false sharing). The consumer never reads the tail and producers never
 * read the head: emptiness and fullness are derived from slot sequences
 * alone.
 */
public final class MpscCommandRing {

    public static final int DEFAULT_CAPACITY = 1 << 16;

    /** Ticket returned by {@link #tryClaim()} when the ring is full. */
    public static final long FULL = -1;

    /** 64 bytes of padding on both sides of the cursor value. */
    private static class CursorPrePad {
        @SuppressWarnings("unused")
        long p1, p2, p3, p4, p5, p6, p7, p8;
    }

    private static class CursorValue extends CursorPrePad {
        @SuppressWarnings("unused")
        volatile long value;
    }

    private static final class Cursor extends CursorValue {
        @SuppressWarnings("unused")
        long q1, q2, q3, q4, q5, q6, q7, q8;
    }

    private static final VarHandle CURSOR;
    private static final VarHandle SEQUENCE;

    static {
        try {
            CURSOR = MethodHandles.lookup()
                    .findVarHandle(CursorValue.class, "value", long.class);
            SEQUENCE = MethodHandles.privateLookupIn(OrderCommand.class, MethodHandles.lookup())
                    .findVarHandle(OrderCommand.class, "sequence", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final OrderCommand[] slots;
    private final int mask;
    private final int capacity;

    /** Producers contend here. */
    private final Cursor tail = new Cursor();

    /** Consumer-owned; producers never touch it. */
    private final Cursor head = new Cursor();

    public MpscCommandRing() {
        this(DEFAULT_CAPACITY);
    }

    public MpscCommandRing(int capacity) {
        if (capacity < 2 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("capacity must be a power of two >= 2: " + capacity);
        }
        this.capacity = capacity;
        this.mask = capacity - 1;
        this.slots = new OrderCommand[capacity];
        for (int i = 0; i < capacity; i++) {
            slots[i] = new OrderCommand(i);
        }
    }

    /**
     * Claims the next slot for writing. Returns a ticket to pass to
     * {@link #slot(long)} and {@link #publish(long)}, or {@link #FULL} when
     * the consumer is more than one lap behind. Multi-producer safe.
     */
    public long tryClaim() {
        long pos = (long) CURSOR.getVolatile(tail);
        for (;;) {
            OrderCommand slot = slots[(int) (pos & mask)];
            long seq = (long) SEQUENCE.getAcquire(slot);
            long dif = seq - pos;
            if (dif == 0) {
                if (CURSOR.compareAndSet(tail, pos, pos + 1)) {
                    return pos;
                }
                pos = (long) CURSOR.getVolatile(tail);
            } else if (dif < 0) {
                return FULL;
            } else {
                pos = (long) CURSOR.getVolatile(tail);
            }
        }
    }

    /** The slot for a claimed ticket. Write its fields, then publish. */
    public OrderCommand slot(long ticket) {
        return slots[(int) (ticket & mask)];
    }

    /** Publishes a claimed slot to the consumer. */
    public void publish(long ticket) {
        SEQUENCE.setRelease(slots[(int) (ticket & mask)], ticket + 1);
    }

    /** Receives commands drained by {@link #drain(CommandHandler, int)}. */
    public interface CommandHandler {
        void onCommand(OrderCommand command);
    }

    /**
     * Drains up to {@code limit} published commands into the handler.
     * Single-consumer only. The slot is recycled right after the handler
     * returns; the handler must not retain a reference to it.
     *
     * @return the number of commands processed
     */
    public int drain(CommandHandler handler, int limit) {
        long pos = (long) CURSOR.getOpaque(head);
        int processed = 0;
        try {
            while (processed < limit) {
                OrderCommand slot = slots[(int) (pos & mask)];
                long seq = (long) SEQUENCE.getAcquire(slot);
                if (seq != pos + 1) {
                    break;
                }
                // Recycle and advance even if the handler throws; a stalled
                // head over a recycled slot would deadlock the consumer.
                try {
                    handler.onCommand(slot);
                } finally {
                    slot.response = null;
                    SEQUENCE.setRelease(slot, pos + capacity);
                    pos++;
                    processed++;
                }
            }
        } finally {
            CURSOR.setOpaque(head, pos);
        }
        return processed;
    }

    public int capacity() {
        return capacity;
    }
}
