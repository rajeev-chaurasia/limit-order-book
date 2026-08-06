package io.github.rajeevchaurasia.orderbook.app.api;

import io.github.rajeevchaurasia.orderbook.book.Side;
import io.github.rajeevchaurasia.orderbook.engine.CancelResult;
import io.github.rajeevchaurasia.orderbook.engine.OrderBookEngine;
import io.github.rajeevchaurasia.orderbook.engine.OrderResult;
import io.github.rajeevchaurasia.orderbook.gateway.EngineBusyException;
import io.github.rajeevchaurasia.orderbook.gateway.EngineGateway;
import io.github.rajeevchaurasia.orderbook.marketdata.BookSnapshot;
import io.github.rajeevchaurasia.orderbook.marketdata.L1Quote;
import io.github.rajeevchaurasia.orderbook.marketdata.StatsSnapshot;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.json.JavalinJackson;

import java.util.List;

/** REST endpoints over the engine gateway. */
public final class OrderBookController {

    private static final String STATUS_ACCEPTED = "ACCEPTED";
    private static final String STATUS_MATCHED = "MATCHED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final int MAX_TRADES_RETURNED = 100;

    private final EngineGateway gateway;

    public OrderBookController(EngineGateway gateway) {
        this.gateway = gateway;
    }

    /** Starts the HTTP server. Port 0 lets the OS pick a free port. */
    public Javalin start(int port) {
        Javalin app = Javalin.create(config -> {
            config.jsonMapper(new JavalinJackson());
            config.bundledPlugins.enableCors(cors -> cors.addRule(it -> it.anyHost()));
        });

        app.get("/health", ctx -> ctx.result("OK"));
        app.get("/api/quote", this::quote);
        app.get("/api/book", this::book);
        app.post("/api/orders", this::submitOrder);
        app.delete("/api/orders/{orderId}", this::cancelOrder);
        app.get("/api/trades", this::trades);
        app.get("/api/stats", this::stats);

        app.exception(EngineBusyException.class, (e, ctx) ->
                ctx.status(HttpStatus.SERVICE_UNAVAILABLE).json(new Dtos.ErrorResponse("Engine busy")));

        return app.start(port);
    }

    private void quote(Context ctx) {
        L1Quote.Quote top = gateway.quote();
        Long bestBid = top.bestBid() == OrderBookEngine.NO_PRICE ? null : top.bestBid();
        Long bestAsk = top.bestAsk() == OrderBookEngine.NO_PRICE ? null : top.bestAsk();
        Long spread = bestBid != null && bestAsk != null ? bestAsk - bestBid : null;
        ctx.json(new Dtos.QuoteDto(bestBid, bestAsk, spread));
    }

    private void book(Context ctx) {
        BookSnapshot snapshot = gateway.book();
        ctx.json(new Dtos.BookDto(toLevels(snapshot.bids()), toLevels(snapshot.asks())));
    }

    private static List<Dtos.PriceLevelDto> toLevels(List<BookSnapshot.Level> levels) {
        return levels.stream()
                .map(level -> new Dtos.PriceLevelDto(level.price(), level.quantity(), level.orders()))
                .toList();
    }

    private void submitOrder(Context ctx) {
        Dtos.OrderRequest request = ctx.bodyAsClass(Dtos.OrderRequest.class);

        Side side;
        try {
            side = Side.valueOf(request.side() == null ? "" : request.side());
        } catch (IllegalArgumentException e) {
            ctx.status(HttpStatus.BAD_REQUEST).result("Invalid side (must be BUY or SELL)");
            return;
        }

        long orderId = request.orderId() != null ? request.orderId() : System.nanoTime();
        long price = request.price() != null ? request.price() : 0L;
        long quantity = request.quantity() != null ? request.quantity() : 0L;

        OrderResult result = gateway.submit(orderId, side, price, quantity);
        if (!result.accepted()) {
            ctx.status(HttpStatus.BAD_REQUEST).json(new Dtos.ErrorResponse(result.reason().name()));
            return;
        }
        String status = result.tradeCount() > 0 ? STATUS_MATCHED : STATUS_ACCEPTED;
        ctx.json(new Dtos.OrderResponse(result.orderId(), status, result.tradeCount(), result.restingQuantity()));
    }

    private void cancelOrder(Context ctx) {
        long orderId;
        try {
            orderId = Long.parseLong(ctx.pathParam("orderId"));
        } catch (NumberFormatException e) {
            ctx.status(HttpStatus.BAD_REQUEST).result("Invalid order ID");
            return;
        }

        CancelResult result = gateway.cancel(orderId);
        if (result.canceled()) {
            ctx.json(new Dtos.CancelResponse(STATUS_CANCELLED, orderId));
        } else {
            ctx.status(HttpStatus.NOT_FOUND).json(new Dtos.ErrorResponse("Order not found"));
        }
    }

    private void trades(Context ctx) {
        List<Dtos.TradeDto> trades = gateway.recentTrades(MAX_TRADES_RETURNED).stream()
                .map(t -> new Dtos.TradeDto(t.buyOrderId(), t.sellOrderId(), t.price(), t.quantity(), t.timestamp()))
                .toList();
        ctx.json(trades);
    }

    private void stats(Context ctx) {
        StatsSnapshot s = gateway.stats();
        ctx.json(new Dtos.StatsDto(
                s.activeOrders(),
                s.poolUtilization(),
                s.poolCapacity(),
                s.bidLevels(),
                s.askLevels(),
                s.totalTrades()));
    }
}
