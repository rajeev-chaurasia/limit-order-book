package io.github.rajeevchaurasia.orderbook.baseline;

import io.github.rajeevchaurasia.orderbook.engine.EngineContractTestBase;
import io.github.rajeevchaurasia.orderbook.engine.EngineListener;
import io.github.rajeevchaurasia.orderbook.engine.OrderBookEngine;

import java.util.function.LongSupplier;

/** Runs the engine contract against the naive baseline (the test oracle). */
class NaiveEngineContractTest extends EngineContractTestBase {

    @Override
    protected OrderBookEngine createEngine(EngineListener listener, LongSupplier clock) {
        return new NaiveMatchingEngine(listener, clock);
    }
}
