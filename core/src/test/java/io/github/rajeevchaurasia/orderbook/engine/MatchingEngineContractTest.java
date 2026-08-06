package io.github.rajeevchaurasia.orderbook.engine;

import io.github.rajeevchaurasia.orderbook.book.LevelPool;
import io.github.rajeevchaurasia.orderbook.book.OrderPool;

import java.util.function.LongSupplier;

/** Runs the engine contract against the optimized single-writer engine. */
class MatchingEngineContractTest extends EngineContractTestBase {

    @Override
    protected OrderBookEngine createEngine(EngineListener listener, LongSupplier clock) {
        return new MatchingEngine(new OrderPool(), new LevelPool(), listener, clock);
    }
}
