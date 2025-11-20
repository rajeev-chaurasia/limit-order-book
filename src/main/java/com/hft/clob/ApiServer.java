package com.hft.clob;

import com.hft.clob.api.OrderBookController;
import com.hft.clob.core.*;
import com.hft.clob.engine.*;

/**
 * Main application with REST API server.
 * Starts the CLOB with a Javalin REST API for external access.
 */
public class ApiServer {

    public static void main(String[] args) {
        System.out.println("=== CLOB REST API Server ===\n");

        // Initialize CLOB
        OrderPool pool = new OrderPool();
        OrderBook book = new OrderBook(pool);
        MatchingEngine engine = new MatchingEngine(book);

        System.out.println("✓ Order Book initialized");
        System.out.printf("  - Pool capacity: %,d orders%n", pool.capacity());

        // Pre-populate with some sample orders for demo
        seedOrderBook(engine);

        // Start REST API server
        int port = 8080;
        OrderBookController api = new OrderBookController(engine);
        api.start(port);

        System.out.println("\n✓ Server ready!");
        System.out.println("  API Endpoints:");
        System.out.println("    GET  http://localhost:8080/api/book   - Order book snapshot");
        System.out.println("    GET  http://localhost:8080/api/quote  - Best bid/ask");
        System.out.println("    POST http://localhost:8080/api/orders - Submit order");
        System.out.println("    GET  http://localhost:8080/api/trades - Recent trades");
        System.out.println("    GET  http://localhost:8080/api/stats  - Statistics");
        System.out.println("\n  Streamlit UI:");
        System.out.println("    Run: streamlit run ui/streamlit_app.py");
    }

    /**
     * Seed the order book with sample orders for demo.
     */
    private static void seedOrderBook(MatchingEngine engine) {
        System.out.println("\n✓ Seeding order book with sample orders...");

        // Add sell orders (asks) at increasing prices
        for (int i = 0; i < 10; i++) {
            long price = 10500 + (i * 10); // $105.00, $105.10, $105.20, ...
            long quantity = 100 + (i * 20);
            engine.processOrder(1000 + i, (byte) 'S', price, quantity);
        }

        // Add buy orders (bids) at decreasing prices
        for (int i = 0; i < 10; i++) {
            long price = 10490 - (i * 10); // $104.90, $104.80, $104.70, ...
            long quantity = 100 + (i * 20);
            engine.processOrder(2000 + i, (byte) 'B', price, quantity);
        }

        System.out.println("  - Added 20 sample orders (10 bids, 10 asks)");
    }
}
