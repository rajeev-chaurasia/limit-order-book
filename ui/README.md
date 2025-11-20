# Streamlit UI for CLOB Order Book

## Quick Start

### 1. Install Python Dependencies

```bash
cd ui
pip install -r requirements.txt
```

### 2. Start the REST API Server (Java)

In the main project directory:
```bash
cd ..
./gradlew run --args="api"
# Or directly:
./gradlew :runApiServer
```

The API will start on `http://localhost:8080`

### 3. Launch Streamlit UI

In a new terminal:
```bash
cd ui
streamlit run streamlit_app.py
```

The UI will open in your browser at `http://localhost:8501`

## Features

### 📊 Real-Time Visualizations
- **Depth Chart**: Cumulative order book depth with bid/ask visualization
- **Order Book Tables**: Top 15 levels of bids and asks
- **Live Metrics**: Best bid, best ask, spread (in $ and bps)

### 📝 Order Submission
- Submit buy/sell orders via sidebar form
- Instant feedback on order status
- See trades executed immediately

### 📈 Trade Feed
- View recent trades with buy/sell order IDs
- Price and quantity for each execution

### 🔄 Auto-Refresh
- Configurable refresh interval (1-10 seconds)
- Toggle on/off for manual control

## API Endpoints Used

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/book` | GET | Order book snapshot (L2 depth) |
| `/api/quote` | GET | Best bid/ask (L1) |
| `/api/orders` | POST | Submit new order |
| `/api/trades` | GET | Recent trades |
| `/api/stats` | GET | System statistics |

## Screenshots

The UI includes:
- Top metrics bar with best bid/ask and spread
- Interactive Plotly depth chart
- Side-by-side bid/ask tables
- Order submission form in sidebar
- System statistics (pool usage, active orders)
- Recent trades table

## Troubleshooting

### Connection Refused
- Ensure the Java API server is running on port 8080
- Check http://localhost:8080/health

### Module Not Found
- Install dependencies: `pip install -r requirements.txt`

### Port Already in Use
- Change the port in `ApiServer.java` (line with `.start(8080)`)
- Update `API_BASE_URL` in `streamlit_app.py`
