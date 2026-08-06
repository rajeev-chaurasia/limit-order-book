package io.github.rajeevchaurasia.orderbook.ring;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MpscCommandRingTest {

    @Test
    void capacityMustBePowerOfTwoAtLeastTwo() {
        assertThrows(IllegalArgumentException.class, () -> new MpscCommandRing(3));
        assertThrows(IllegalArgumentException.class, () -> new MpscCommandRing(6));
        assertThrows(IllegalArgumentException.class, () -> new MpscCommandRing(1));
        assertThrows(IllegalArgumentException.class, () -> new MpscCommandRing(0));
        assertEquals(4, new MpscCommandRing(4).capacity());
        assertEquals(MpscCommandRing.DEFAULT_CAPACITY, new MpscCommandRing().capacity());
    }

    @Test
    void publishDrainRoundtripDeliversFields() {
        MpscCommandRing ring = new MpscCommandRing(8);

        long ticket = ring.tryClaim();
        assertNotEquals(MpscCommandRing.FULL, ticket);
        OrderCommand slot = ring.slot(ticket);
        slot.type = CommandType.NEW;
        slot.orderId = 7;
        slot.price = 100;
        slot.quantity = 5;
        ring.publish(ticket);

        List<long[]> seen = new ArrayList<>();
        AtomicReference<CommandType> seenType = new AtomicReference<>();
        int drained = ring.drain(command -> {
            seenType.set(command.type);
            seen.add(new long[] {command.orderId, command.price, command.quantity});
        }, 10);

        assertEquals(1, drained);
        assertEquals(1, seen.size());
        assertEquals(CommandType.NEW, seenType.get());
        assertEquals(7, seen.get(0)[0]);
        assertEquals(100, seen.get(0)[1]);
        assertEquals(5, seen.get(0)[2]);
    }

    @Test
    void fullDetection() {
        MpscCommandRing ring = new MpscCommandRing(4);
        for (int i = 0; i < 4; i++) {
            long ticket = ring.tryClaim();
            assertNotEquals(MpscCommandRing.FULL, ticket);
            ring.publish(ticket);
        }
        assertEquals(MpscCommandRing.FULL, ring.tryClaim());
    }

    @Test
    void wraparoundPreservesOrderAcrossLaps() {
        MpscCommandRing ring = new MpscCommandRing(4);
        long[] produced = {0};
        long[] consumed = {0};
        AtomicReference<String> failure = new AtomicReference<>();

        for (int lap = 0; lap < 3; lap++) {
            for (int i = 0; i < 4; i++) {
                long ticket = ring.tryClaim();
                assertNotEquals(MpscCommandRing.FULL, ticket);
                OrderCommand slot = ring.slot(ticket);
                slot.type = CommandType.NEW;
                slot.orderId = produced[0]++;
                ring.publish(ticket);
            }
            int drained = ring.drain(command -> {
                if (command.orderId != consumed[0]) {
                    failure.compareAndSet(null,
                            "expected orderId " + consumed[0] + " but saw " + command.orderId);
                }
                consumed[0]++;
            }, 4);
            assertEquals(4, drained);
        }
        assertNull(failure.get(), failure.get());
        assertEquals(12, produced[0]);
        assertEquals(12, consumed[0]);
    }

    @Test
    void drainLimitRespected() {
        MpscCommandRing ring = new MpscCommandRing(4);
        for (int i = 0; i < 4; i++) {
            long ticket = ring.tryClaim();
            OrderCommand slot = ring.slot(ticket);
            slot.type = CommandType.NEW;
            slot.orderId = i;
            ring.publish(ticket);
        }

        List<Long> ids = new ArrayList<>();
        assertEquals(2, ring.drain(command -> ids.add(command.orderId), 2));
        assertEquals(List.of(0L, 1L), ids);

        ids.clear();
        assertEquals(2, ring.drain(command -> ids.add(command.orderId), 10));
        assertEquals(List.of(2L, 3L), ids);
    }

    @Test
    void drainEmptyReturnsZero() {
        MpscCommandRing ring = new MpscCommandRing(4);
        assertEquals(0, ring.drain(command -> {
            throw new AssertionError("nothing should be drained");
        }, 10));
    }

    @Test
    void responseRefClearedAfterDrain() {
        MpscCommandRing ring = new MpscCommandRing(4);

        long ticket = ring.tryClaim();
        OrderCommand slot = ring.slot(ticket);
        slot.type = CommandType.NEW;
        slot.orderId = 1;
        slot.response = new CompletableFuture<>();
        ring.publish(ticket);

        AtomicReference<OrderCommand> captured = new AtomicReference<>();
        AtomicReference<CompletableFuture<Object>> responseInHandler = new AtomicReference<>();
        assertEquals(1, ring.drain(command -> {
            captured.set(command);
            responseInHandler.set(command.response);
        }, 10));

        assertNotNull(responseInHandler.get(), "response must still be set inside the handler");
        assertNotNull(captured.get());
        assertNull(captured.get().response, "drain must clear the response reference on recycle");
    }

    @Test
    void handlerExceptionDoesNotWedgeRing() {
        MpscCommandRing ring = new MpscCommandRing(4);
        for (int i = 1; i <= 2; i++) {
            long ticket = ring.tryClaim();
            OrderCommand slot = ring.slot(ticket);
            slot.type = CommandType.NEW;
            slot.orderId = i;
            ring.publish(ticket);
        }

        RuntimeException boom = assertThrows(RuntimeException.class, () -> ring.drain(command -> {
            throw new RuntimeException("handler failure on orderId " + command.orderId);
        }, 10));
        assertEquals("handler failure on orderId 1", boom.getMessage());

        // The failed slot was recycled and the head advanced; the second
        // command is still deliverable.
        List<Long> ids = new ArrayList<>();
        assertEquals(1, ring.drain(command -> ids.add(command.orderId), 10));
        assertEquals(List.of(2L), ids);

        long ticket = ring.tryClaim();
        assertNotEquals(MpscCommandRing.FULL, ticket);
        OrderCommand slot = ring.slot(ticket);
        slot.type = CommandType.NEW;
        slot.orderId = 3;
        ring.publish(ticket);

        ids.clear();
        assertEquals(1, ring.drain(command -> ids.add(command.orderId), 10));
        assertEquals(List.of(3L), ids);
    }
}
