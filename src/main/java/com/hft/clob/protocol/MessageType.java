package com.hft.clob.protocol;

/**
 * Message types for binary protocol.
 */
public enum MessageType {
    ADD('A'),
    CANCEL('C'),
    MODIFY('M'),
    EXECUTE('E');

    public final byte code;

    MessageType(char code) {
        this.code = (byte) code;
    }

    public static MessageType fromByte(byte code) {
        for (MessageType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Invalid message type: " + (char) code);
    }
}
