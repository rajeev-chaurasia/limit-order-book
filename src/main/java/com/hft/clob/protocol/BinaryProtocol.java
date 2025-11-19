package com.hft.clob.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Binary protocol parser using fixed-width 32-byte messages.
 * Avoids JSON/String parsing overhead for ultra-low latency.
 * 
 * Message Layout:
 * | Offset | Field | Type | Description |
 * |--------|----------|-------|-----------------------|
 * | 0 | Type | byte | 'A', 'C', 'M', 'E' |
 * | 1 | Side | byte | 'B' (Buy), 'S' (Sell) |
 * | 2-9 | OrderId | long | Unique ID |
 * | 10-17 | Price | long | Fixed-point price |
 * | 18-25 | Quantity | long | Number of shares |
 * | 26-31 | Padding | byte[]| Reserved/alignment |
 * 
 * Performance: ~10ns to decode on modern CPUs
 */
public class BinaryProtocol {
    public static final int MESSAGE_SIZE = 32;

    /**
     * Message fields (public for zero-overhead access).
     */
    public static class Message {
        public byte type; // MessageType
        public byte side; // 'B' or 'S'
        public long orderId;
        public long price;
        public long quantity;

        @Override
        public String toString() {
            return String.format("Message[type=%c, side=%c, id=%d, price=%d, qty=%d]",
                    (char) type, (char) side, orderId, price, quantity);
        }
    }

    /**
     * Decode binary message from ByteBuffer.
     * 
     * @param buffer ByteBuffer positioned at message start
     * @return Decoded message
     */
    public static Message decode(ByteBuffer buffer) {
        if (buffer.remaining() < MESSAGE_SIZE) {
            throw new IllegalArgumentException("Buffer too small: " + buffer.remaining());
        }

        Message msg = new Message();

        // Use LITTLE_ENDIAN for consistency (Intel/AMD CPUs)
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        msg.type = buffer.get(); // Offset 0
        msg.side = buffer.get(); // Offset 1
        msg.orderId = buffer.getLong(); // Offset 2-9
        msg.price = buffer.getLong(); // Offset 10-17
        msg.quantity = buffer.getLong(); // Offset 18-25

        // Skip padding (6 bytes)
        buffer.position(buffer.position() + 6);

        return msg;
    }

    /**
     * Encode message to ByteBuffer.
     * 
     * @param type     Message type
     * @param side     Order side
     * @param orderId  Order ID
     * @param price    Price
     * @param quantity Quantity
     * @return ByteBuffer containing encoded message
     */
    public static ByteBuffer encode(byte type, byte side, long orderId, long price, long quantity) {
        ByteBuffer buffer = ByteBuffer.allocate(MESSAGE_SIZE);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        buffer.put(type);
        buffer.put(side);
        buffer.putLong(orderId);
        buffer.putLong(price);
        buffer.putLong(quantity);

        // Padding (6 bytes of zeros)
        buffer.put(new byte[6]);

        buffer.flip();
        return buffer;
    }

    /**
     * Encode ADD message.
     */
    public static ByteBuffer encodeAdd(byte side, long orderId, long price, long quantity) {
        return encode(MessageType.ADD.code, side, orderId, price, quantity);
    }

    /**
     * Encode CANCEL message.
     */
    public static ByteBuffer encodeCancel(long orderId) {
        return encode(MessageType.CANCEL.code, (byte) 0, orderId, 0, 0);
    }

    /**
     * Encode MODIFY message.
     */
    public static ByteBuffer encodeModify(byte side, long orderId, long newPrice, long newQuantity) {
        return encode(MessageType.MODIFY.code, side, orderId, newPrice, newQuantity);
    }
}
