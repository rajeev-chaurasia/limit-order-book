package io.github.rajeevchaurasia.orderbook.app.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rajeevchaurasia.orderbook.app.ApiServer;
import io.github.rajeevchaurasia.orderbook.app.EngineFacade;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down the JSON wire contract consumed by the deployed Streamlit UI:
 * exact field names, status strings, and error shapes. Runs against a real
 * HTTP server on an ephemeral port with a freshly seeded book per test.
 */
class ApiContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static final Set<String> QUOTE_FIELDS = Set.of("bestBid", "bestAsk", "spread");
    private static final Set<String> BOOK_FIELDS = Set.of("bids", "asks");
    private static final Set<String> LEVEL_FIELDS = Set.of("price", "quantity", "orders");
    private static final Set<String> ORDER_FIELDS = Set.of("orderId", "status", "tradesCount", "remainingQuantity");
    private static final Set<String> CANCEL_FIELDS = Set.of("status", "orderId");
    private static final Set<String> TRADE_FIELDS = Set.of("buyOrderId", "sellOrderId", "price", "quantity", "timestamp");
    private static final Set<String> STATS_FIELDS = Set.of(
            "activeOrders", "poolUtilization", "poolCapacity", "bidLevels", "askLevels", "totalTrades");

    private Javalin app;
    private String baseUrl;

    @BeforeEach
    void startServer() {
        EngineFacade facade = new EngineFacade();
        ApiServer.seedDemoBook(facade);
        app = new OrderBookController(facade).start(0);
        baseUrl = "http://localhost:" + app.port();
    }

    @AfterEach
    void stopServer() {
        app.stop();
    }

    @Test
    void healthReturnsPlainOk() throws Exception {
        HttpResponse<String> response = get("/health");
        assertEquals(200, response.statusCode());
        assertEquals("OK", response.body());
    }

    @Test
    void quoteHasExactFieldsAndSeededValues() throws Exception {
        HttpResponse<String> response = get("/api/quote");
        assertEquals(200, response.statusCode());
        JsonNode quote = MAPPER.readTree(response.body());
        assertEquals(QUOTE_FIELDS, fieldNames(quote));
        assertEquals(10490, quote.get("bestBid").asLong());
        assertEquals(10500, quote.get("bestAsk").asLong());
        assertEquals(10, quote.get("spread").asLong());
    }

    @Test
    void bookHasExactFieldsAndOrdering() throws Exception {
        HttpResponse<String> response = get("/api/book");
        assertEquals(200, response.statusCode());
        JsonNode book = MAPPER.readTree(response.body());
        assertEquals(BOOK_FIELDS, fieldNames(book));

        JsonNode bids = book.get("bids");
        JsonNode asks = book.get("asks");
        assertEquals(10, bids.size());
        assertEquals(10, asks.size());
        assertEquals(LEVEL_FIELDS, fieldNames(bids.get(0)));
        assertEquals(LEVEL_FIELDS, fieldNames(asks.get(0)));

        for (int i = 1; i < bids.size(); i++) {
            assertTrue(bids.get(i).get("price").asLong() < bids.get(i - 1).get("price").asLong(),
                    "bids must be best-first descending");
        }
        for (int i = 1; i < asks.size(); i++) {
            assertTrue(asks.get(i).get("price").asLong() > asks.get(i - 1).get("price").asLong(),
                    "asks must be best-first ascending");
        }
    }

    @Test
    void nonCrossingOrderIsAccepted() throws Exception {
        HttpResponse<String> response = postOrder("{\"orderId\": 5001, \"side\": \"BUY\", \"price\": 10480, \"quantity\": 50}");
        assertEquals(200, response.statusCode());
        JsonNode result = MAPPER.readTree(response.body());
        assertEquals(ORDER_FIELDS, fieldNames(result));
        assertEquals(5001, result.get("orderId").asLong());
        assertEquals("ACCEPTED", result.get("status").asText());
        assertEquals(0, result.get("tradesCount").asInt());
        assertEquals(50, result.get("remainingQuantity").asLong());
    }

    @Test
    void crossingOrderIsMatched() throws Exception {
        HttpResponse<String> response = postOrder("{\"orderId\": 5002, \"side\": \"BUY\", \"price\": 10500, \"quantity\": 50}");
        assertEquals(200, response.statusCode());
        JsonNode result = MAPPER.readTree(response.body());
        assertEquals(ORDER_FIELDS, fieldNames(result));
        assertEquals("MATCHED", result.get("status").asText());
        assertTrue(result.get("tradesCount").asInt() >= 1);
        assertEquals(0, result.get("remainingQuantity").asLong());
    }

    @Test
    void orderIdDefaultsWhenAbsent() throws Exception {
        HttpResponse<String> response = postOrder("{\"side\": \"SELL\", \"price\": 10600, \"quantity\": 5}");
        assertEquals(200, response.statusCode());
        JsonNode result = MAPPER.readTree(response.body());
        assertEquals("ACCEPTED", result.get("status").asText());
        assertTrue(result.get("orderId").asLong() > 0);
    }

    @Test
    void invalidSideIsBadRequest() throws Exception {
        HttpResponse<String> response = postOrder("{\"side\": \"HOLD\", \"price\": 10480, \"quantity\": 50}");
        assertEquals(400, response.statusCode());
        assertEquals("Invalid side (must be BUY or SELL)", response.body());
    }

    @Test
    void duplicateActiveOrderIdIsRejected() throws Exception {
        // 1000 is a seeded resting ask; resubmitting the id must be refused.
        HttpResponse<String> response = postOrder("{\"orderId\": 1000, \"side\": \"BUY\", \"price\": 10480, \"quantity\": 10}");
        assertEquals(400, response.statusCode());
        JsonNode error = MAPPER.readTree(response.body());
        assertEquals("DUPLICATE_ORDER_ID", error.get("error").asText());
    }

    @Test
    void cancelExistingOrderReturnsCancelled() throws Exception {
        HttpResponse<String> response = delete("/api/orders/1000");
        assertEquals(200, response.statusCode());
        JsonNode result = MAPPER.readTree(response.body());
        assertEquals(CANCEL_FIELDS, fieldNames(result));
        assertEquals("CANCELLED", result.get("status").asText());
        assertEquals(1000, result.get("orderId").asLong());
    }

    @Test
    void cancelUnknownOrderIsNotFound() throws Exception {
        HttpResponse<String> response = delete("/api/orders/99999");
        assertEquals(404, response.statusCode());
        JsonNode error = MAPPER.readTree(response.body());
        assertEquals(Set.of("error"), fieldNames(error));
    }

    @Test
    void cancelNonNumericIdIsBadRequest() throws Exception {
        HttpResponse<String> response = delete("/api/orders/abc");
        assertEquals(400, response.statusCode());
        assertEquals("Invalid order ID", response.body());
    }

    @Test
    void tradesAfterMatchHaveExactFields() throws Exception {
        postOrder("{\"orderId\": 5003, \"side\": \"BUY\", \"price\": 10500, \"quantity\": 30}");

        HttpResponse<String> response = get("/api/trades");
        assertEquals(200, response.statusCode());
        JsonNode trades = MAPPER.readTree(response.body());
        assertTrue(trades.isArray());
        assertFalse(trades.isEmpty());
        assertEquals(TRADE_FIELDS, fieldNames(trades.get(trades.size() - 1)));
        assertEquals(5003, trades.get(trades.size() - 1).get("buyOrderId").asLong());
    }

    @Test
    void statsHaveExactFields() throws Exception {
        HttpResponse<String> response = get("/api/stats");
        assertEquals(200, response.statusCode());
        JsonNode stats = MAPPER.readTree(response.body());
        assertEquals(STATS_FIELDS, fieldNames(stats));
        assertEquals(20, stats.get("activeOrders").asInt());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postOrder(String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/orders"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).DELETE().build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
