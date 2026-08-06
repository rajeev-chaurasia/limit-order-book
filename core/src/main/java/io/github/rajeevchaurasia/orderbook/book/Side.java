package io.github.rajeevchaurasia.orderbook.book;

/**
 * Order side. The byte code is the wire representation used by binary
 * commands; the enum is used everywhere else.
 */
public enum Side {
    BUY((byte) 'B'),
    SELL((byte) 'S');

    public final byte code;

    Side(byte code) {
        this.code = code;
    }

    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }

    public static Side fromCode(byte code) {
        if (code == BUY.code) {
            return BUY;
        }
        if (code == SELL.code) {
            return SELL;
        }
        throw new IllegalArgumentException("Unknown side code: " + code);
    }
}
