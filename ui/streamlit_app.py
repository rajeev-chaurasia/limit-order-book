import streamlit as st
import requests
import pandas as pd
import plotly.graph_objects as go
from plotly.subplots import make_subplots
import time
import os

# Configuration - supports both local and deployed modes
API_BASE_URL = os.getenv("API_BASE_URL", "http://localhost:8080/api")

st.set_page_config(
    page_title="CLOB Order Book Visualizer",
    page_icon="📊",
    layout="wide",
    initial_sidebar_state="expanded"
)

# Custom CSS
st.markdown("""
<style>
    .metric-card {
        background-color: #f0f2f6;
        padding: 20px;
        border-radius: 10px;
        box-shadow: 0 2px 4px rgba(0,0,0,0.1);
    }
    .buy-price {
        color: #00C805;
        font-weight: bold;
    }
    .sell-price {
        color: #FF3333;
        font-weight: bold;
    }
</style>
""", unsafe_allow_html=True)

def fetch_data(endpoint):
    """Fetch data from API."""
    try:
        response = requests.get(f"{API_BASE_URL}/{endpoint}", timeout=2)
        if response.status_code == 200:
            return response.json()
    except:
        pass
    return None

def submit_order(side, price, quantity):
    """Submit order to the order book."""
    try:
        payload = {
            "side": side,
            "price": int(price * 100),  # Convert to fixed-point
            "quantity": quantity
        }
        response = requests.post(f"{API_BASE_URL}/orders", json=payload, timeout=2)
        return response.status_code == 200, response.json() if response.status_code == 200 else None
    except Exception as e:
        return False, str(e)

def format_price(price_fp):
    """Convert fixed-point price to decimal."""
    return price_fp / 100.0

def create_depth_chart(bids, asks):
    """Create depth chart visualization."""
    fig = make_subplots(
        rows=1, cols=1,
        subplot_titles=["Order Book Depth"]
    )
    
    if bids:
        bid_df = pd.DataFrame(bids)
        bid_df['price_decimal'] = bid_df['price'].apply(format_price)
        bid_df['cumulative_qty'] = bid_df['quantity'].cumsum()
        
        fig.add_trace(go.Scatter(
            x=bid_df['price_decimal'],
            y=bid_df['cumulative_qty'],
            fill='tozeroy',
            name='Bids',
            line=dict(color='#00C805', width=2),
            fillcolor='rgba(0, 200, 5, 0.3)'
        ))
    
    if asks:
        ask_df = pd.DataFrame(asks)
        ask_df['price_decimal'] = ask_df['price'].apply(format_price)
        ask_df['cumulative_qty'] = ask_df['quantity'].cumsum()
        
        fig.add_trace(go.Scatter(
            x=ask_df['price_decimal'],
            y=ask_df['cumulative_qty'],
            fill='tozeroy',
            name='Asks',
            line=dict(color='#FF3333', width=2),
            fillcolor='rgba(255, 51, 51, 0.3)'
        ))
    
    fig.update_layout(
        height=400,
        xaxis_title="Price ($)",
        yaxis_title="Cumulative Quantity",
        hovermode='x unified',
        showlegend=True,
        template='plotly_white'
    )
    
    return fig

def create_order_book_table(levels, side):
    """Create order book table for bids or asks."""
    if not levels:
        return pd.DataFrame()
    
    df = pd.DataFrame(levels)
    df['Price'] = df['price'].apply(format_price)
    df['Quantity'] = df['quantity']
    df['Orders'] = df['orders']
    
    return df[['Price', 'Quantity', 'Orders']]

# Title
st.title("📊 High-Performance CLOB Order Book")
st.markdown("Real-time visualization of concurrent limit order book")

# Sidebar - Order Submission
with st.sidebar:
    st.header("📝 Submit Order")
    
    order_side = st.radio("Side", ["BUY", "SELL"])
    order_price = st.number_input("Price ($)", min_value=0.01, value=105.00, step=0.01, format="%.2f")
    order_quantity = st.number_input("Quantity", min_value=1, value=100, step=10)
    
    if st.button("Submit Order", type="primary", use_container_width=True):
        success, result = submit_order(order_side, order_price, order_quantity)
        if success:
            st.success(f"✅ Order {result.get('status', 'SUBMITTED')}")
            if result.get('tradesCount', 0) > 0:
                st.info(f"🔄 {result['tradesCount']} trade(s) executed")
        else:
            st.error(f"❌ Failed: {result}")
    
    st.divider()

# Auto-refresh controls (outside sidebar to prevent duplication)
auto_refresh = st.sidebar.checkbox("Auto-refresh", value=False, key="auto_refresh_checkbox")
refresh_interval = 2
if auto_refresh:
    refresh_interval = st.sidebar.slider("Refresh interval (s)", 1, 10, 2, key="refresh_slider")


# Main content
quote = fetch_data("quote")
book = fetch_data("book")
stats = fetch_data("stats")

# Check if backend is available
if quote is None and book is None and stats is None:
    st.error("⚠️ **Backend API is not available**")
    st.info(f"""
    The Streamlit UI is trying to connect to: `{API_BASE_URL}`
    
    **For local development:**
    - Make sure the Java backend is running: `./gradlew runApiServer`
    
    **For deployment:**
    - Deploy the Java backend separately (e.g., to Render, Railway, or Heroku)
    - Set the `API_BASE_URL` environment variable in Streamlit Cloud settings
    """)
    st.stop()


# Top metrics
if quote:
    col1, col2, col3 = st.columns(3)
    
    with col1:
        best_bid = format_price(quote['bestBid']) if quote.get('bestBid') else 0
        st.metric("Best Bid", f"${best_bid:.2f}", delta=None, delta_color="normal")
    
    with col2:
        best_ask = format_price(quote['bestAsk']) if quote.get('bestAsk') else 0
        st.metric("Best Ask", f"${best_ask:.2f}", delta=None, delta_color="inverse")
    
    with col3:
        spread = format_price(quote['spread']) if quote.get('spread') else 0
        spread_bps = (spread / best_ask * 10000) if best_ask > 0 else 0
        st.metric("Spread", f"${spread:.2f}", f"{spread_bps:.1f} bps")

st.divider()

# Main visualization
if book:
    bids = book.get('bids', [])
    asks = book.get('asks', [])
    
    # Depth chart
    st.subheader("📈 Order Book Depth Chart")
    depth_chart = create_depth_chart(bids, asks)
    st.plotly_chart(depth_chart, use_container_width=True)
    
    st.divider()
    
    # Order book tables
    col1, col2 = st.columns(2)
    
    with col1:
        st.subheader("💚 Bids (Buy Orders)")
        bid_df = create_order_book_table(bids, 'BUY')
        if not bid_df.empty:
            st.dataframe(
                bid_df.head(15),
                use_container_width=True,
                hide_index=True,
                height=400
            )
        else:
            st.info("No bids in the order book")
    
    with col2:
        st.subheader("❤️ Asks (Sell Orders)")
        ask_df = create_order_book_table(asks, 'SELL')
        if not ask_df.empty:
            st.dataframe(
                ask_df.head(15),
                use_container_width=True,
                hide_index=True,
                height=400
            )
        else:
            st.info("No asks in the order book")

st.divider()

# Statistics
if stats:
    st.subheader("📊 System Statistics")
    col1, col2, col3, col4 = st.columns(4)
    
    with col1:
        st.metric("Active Orders", stats.get('activeOrders', 0))
    
    with col2:
        pool_util = stats.get('poolUtilization', 0)
        pool_cap = stats.get('poolCapacity', 100000)
        util_pct = (pool_util / pool_cap * 100) if pool_cap > 0 else 0
        st.metric("Pool Usage", f"{pool_util:,}", f"{util_pct:.2f}%")
    
    with col3:
        st.metric("Bid Levels", stats.get('bidLevels', 0))
    
    with col4:
        st.metric("Ask Levels", stats.get('askLevels', 0))

# Recent trades
trades = fetch_data("trades")
if trades and len(trades) > 0:
    st.subheader("🔄 Recent Trades")
    trades_df = pd.DataFrame(trades)
    
    # Format the data properly
    trades_df = trades_df.tail(10).copy()  # Get last 10 trades
    trades_df['Buy Order ID'] = trades_df['buyOrderId'].apply(lambda x: f"{x:,}")
    trades_df['Sell Order ID'] = trades_df['sellOrderId'].apply(lambda x: f"{x:,}")
    trades_df['Price ($)'] = trades_df['price'].apply(lambda x: f"${x/100:.2f}")
    trades_df['Qty'] = trades_df['quantity'].astype(int)
    
    # Display the formatted table
    st.dataframe(
        trades_df[['Buy Order ID', 'Sell Order ID', 'Price ($)', 'Qty']],
        use_container_width=True,
        hide_index=True
    )
else:
    st.info("No trades executed yet. Submit orders to see trades appear here.")


# Footer
st.divider()
st.caption("High-Performance Concurrent Limit Order Book • Built with Java + Streamlit")

# Auto-refresh
if auto_refresh:
    time.sleep(refresh_interval)
    st.rerun()
