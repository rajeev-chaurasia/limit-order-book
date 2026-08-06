package io.github.rajeevchaurasia.orderbook.ring;

/**
 * Commands the engine thread understands. NEW and CANCEL are the data plane;
 * the snapshot types and SHUTDOWN are the control plane.
 */
public enum CommandType {
    NEW,
    CANCEL,
    SNAPSHOT_BOOK,
    SNAPSHOT_TRADES,
    SNAPSHOT_STATS,
    SHUTDOWN
}
