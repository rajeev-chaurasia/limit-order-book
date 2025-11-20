package com.hft.clob.api;

import com.google.gson.Gson;
import com.hft.clob.core.*;
import com.hft.clob.engine.*;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * REST API for CLOB using Javalin.
 * Provides endpoints for order submission, book snapshots, and trade feed.
 */
public class OrderBookController {
    private final MatchingEngine engine;
    private final OrderBook book;
    private final Gson gson;
    private final Queue<TradeDTO> recentTrades;
    private final int maxRecentTrades = 100;

    public OrderBookController(MatchingEngine engine) {
        this.engine = engine;
        this.book = engine.getBook();
        this.gson = new Gson();
        this.recentTrades = new ConcurrentLinkedQueue<>();
    }

    /**
     * Start the API server on specified port.
     */
    public Javalin start(int port) {
        Javalin app = Javalin.create(config -> {
            config.plugins.enableCors(cors -> {
                cors.add(it -> {
                    it.anyHost();
                });
            });
        }).start(port);

        // Health check
        app.get("/health", ctx -> ctx.result("OK"));

        // Get order book snapshot (L2 depth)
        app.get("/api/book", this::getOrderBook);

        // Get best bid/ask (L1)
        app.get("/api/quote", this::getQuote);

        // Submit new order
        app.post("/api/orders", this::submitOrder);

        // Cancel order
        app.delete("/api/orders/{orderId}", this::cancelOrder);

        // Get recent trades
        app.get("/api/trades", this::getTrades);

        // Get statistics
        app.get("/api/stats", this::getStats);

        System.out.println("✓ REST API started on http://localhost:" + port);
        return app;
    }

    /**
     * Get order book snapshot (L2 depth).
     */
    private void getOrderBook(Context ctx) {
        OrderBookSnapshot snapshot = new OrderBookSnapshot();

        // Collect bids (price descending)
        book.getBids().forEach((price, level) -> {
            snapshot.bids.add(new PriceLevel(price, level.getTotalQuantity(), level.getSize()));
        });

        // Collect asks (price ascending)
        book.getAsks().forEach((price, level) -> {
            snapshot.asks.add(new PriceLevel(price, level.getTotalQuantity(), level.getSize()));
        });

        ctx.json(snapshot);
    }

    /**
     * Get best bid/ask (L1 quote).
     */
    private void getQuote(Context ctx) {
        QuoteDTO quote = new QuoteDTO();
        quote.bestBid = book.getBestBid();
        quote.bestAsk = book.getBestAsk();

        if (quote.bestBid != null && quote.bestAsk != null) {
            quote.spread = quote.bestAsk - quote.bestBid;
        }

        ctx.json(quote);
    }

    /**
     * Submit new order.
     */
    private void submitOrder(Context ctx) {
        try {
            OrderRequest req = gson.fromJson(ctx.body(), OrderRequest.class);

            // Validate
            if (req.side == null || (!req.side.equals("BUY") && !req.side.equals("SELL"))) {
                ctx.status(400).result("Invalid side (must be BUY or SELL)");
                return;
            }

            byte sideCode = req.side.equals("BUY") ? (byte) 'B' : (byte) 'S';
            long orderId = req.orderId != null ? req.orderId : System.nanoTime();

            // Process order
            List<Trade> trades = engine.processOrder(orderId, sideCode, req.price, req.quantity);

            // Record trades
            for (Trade trade : trades) {
                TradeDTO dto = new TradeDTO(
                        trade.buyOrderId,
                        trade.sellOrderId,
                        trade.price,
                        trade.quantity,
                        trade.timestamp);
                recentTrades.offer(dto);

                // Keep only recent trades
                while (recentTrades.size() > maxRecentTrades) {
                    recentTrades.poll();
                }
            }

            OrderResponse response = new OrderResponse();
            response.orderId = orderId;
            response.status = trades.isEmpty() ? "ACCEPTED" : "MATCHED";
            response.tradesCount = trades.size();
            response.remainingQuantity = req.quantity - trades.stream().mapToLong(t -> t.quantity).sum();

            ctx.json(response);

        } catch (Exception e) {
            ctx.status(400).result("Invalid request: " + e.getMessage());
        }
    }

    /**
     * Cancel order.
     */
    private void cancelOrder(Context ctx) {
        try {
            long orderId = Long.parseLong(ctx.pathParam("orderId"));
            boolean cancelled = engine.cancelOrder(orderId);

            if (cancelled) {
                ctx.json(Map.of("status", "CANCELLED", "orderId", orderId));
            } else {
                ctx.status(404).json(Map.of("error", "Order not found"));
            }
        } catch (NumberFormatException e) {
            ctx.status(400).result("Invalid order ID");
        }
    }

    /**
     * Get recent trades.
     */
    private void getTrades(Context ctx) {
        ctx.json(new ArrayList<>(recentTrades));
    }

    /**
     * Get system statistics.
     */
    private void getStats(Context ctx) {
        StatsDTO stats = new StatsDTO();
        stats.activeOrders = book.getIndex().size();
        stats.poolUtilization = book.getPool().capacity() - book.getPool().availableOrders();
        stats.poolCapacity = book.getPool().capacity();
        stats.bidLevels = book.getBids().size();
        stats.askLevels = book.getAsks().size();
        stats.totalTrades = recentTrades.size();

        ctx.json(stats);
    }

    // DTOs
    static class OrderBookSnapshot {
        public List<PriceLevel> bids = new ArrayList<>();
        public List<PriceLevel> asks = new ArrayList<>();
    }

    static class PriceLevel {
        public long price;
        public long quantity;
        public int orders;

        PriceLevel(long price, long quantity, int orders) {
            this.price = price;
            this.quantity = quantity;
            this.orders = orders;
        }
    }

    static class QuoteDTO {
        public Long bestBid;
        public Long bestAsk;
        public Long spread;
    }

    static class OrderRequest {
        public Long orderId;
        public String side; // "BUY" or "SELL"
        public long price;
        public long quantity;
    }

    static class OrderResponse {
        public long orderId;
        public String status;
        public int tradesCount;
        public long remainingQuantity;
    }

    static class TradeDTO {
        public long buyOrderId;
        public long sellOrderId;
        public long price;
        public long quantity;
        public long timestamp;

        TradeDTO(long buyOrderId, long sellOrderId, long price, long quantity, long timestamp) {
            this.buyOrderId = buyOrderId;
            this.sellOrderId = sellOrderId;
            this.price = price;
            this.quantity = quantity;
            this.timestamp = timestamp;
        }
    }

    static class StatsDTO {
        public int activeOrders;
        public int poolUtilization;
        public int poolCapacity;
        public int bidLevels;
        public int askLevels;
        public int totalTrades;
    }
}
