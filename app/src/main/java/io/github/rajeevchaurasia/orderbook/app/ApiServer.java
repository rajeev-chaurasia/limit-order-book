package io.github.rajeevchaurasia.orderbook.app;

import io.github.rajeevchaurasia.orderbook.app.api.OrderBookController;
import io.github.rajeevchaurasia.orderbook.book.Side;

/** Entry point: seeds a demo book and serves the REST API. */
public final class ApiServer {

    private static final int DEFAULT_PORT = 8080;
    private static final String PORT_ENV_VAR = "PORT";

    private static final int SEED_LEVELS = 10;
    private static final long BASE_ASK_PRICE = 10_500;
    private static final long BASE_BID_PRICE = 10_490;
    private static final long PRICE_STEP = 10;
    private static final long BASE_QUANTITY = 100;
    private static final long QUANTITY_STEP = 20;
    private static final long ASK_ID_BASE = 1_000;
    private static final long BID_ID_BASE = 2_000;

    private ApiServer() {
    }

    public static void main(String[] args) {
        EngineFacade facade = new EngineFacade();
        seedDemoBook(facade);

        int port = resolvePort();
        new OrderBookController(facade).start(port);

        System.out.println("Limit order book API server started on port " + port);
        System.out.println("Health check:  GET http://localhost:" + port + "/health");
        System.out.println("API base path: http://localhost:" + port + "/api");
        System.out.println("Demo book seeded: best bid " + BASE_BID_PRICE + ", best ask " + BASE_ASK_PRICE);
    }

    /** Seeds ten resting orders per side around a 10490/10500 market. */
    public static void seedDemoBook(EngineFacade facade) {
        for (int i = 0; i < SEED_LEVELS; i++) {
            facade.submit(ASK_ID_BASE + i, Side.SELL,
                    BASE_ASK_PRICE + i * PRICE_STEP, BASE_QUANTITY + i * QUANTITY_STEP);
        }
        for (int i = 0; i < SEED_LEVELS; i++) {
            facade.submit(BID_ID_BASE + i, Side.BUY,
                    BASE_BID_PRICE - i * PRICE_STEP, BASE_QUANTITY + i * QUANTITY_STEP);
        }
    }

    private static int resolvePort() {
        String env = System.getenv(PORT_ENV_VAR);
        if (env == null || env.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            return Integer.parseInt(env.trim());
        } catch (NumberFormatException e) {
            System.err.println("Ignoring invalid " + PORT_ENV_VAR + " value: " + env);
            return DEFAULT_PORT;
        }
    }
}
