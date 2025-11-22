#!/bin/bash

# CLOB Quick Start Script
# Launches the REST API server and Streamlit UI

set -e

echo "=== CLOB Streamlit UI Launcher ==="
echo ""

# Check if Python is installed
if ! command -v python3 &> /dev/null; then
    echo "❌ Python 3 is not installed. Please install Python 3.8+ first."
    exit 1
fi

# Check if Streamlit dependencies are installed
echo "📦 Checking Python dependencies..."
if ! python3 -c "import streamlit" &> /dev/null; then
    echo "Installing Python dependencies..."
    cd ui
    pip3 install -r requirements.txt
    cd ..
fi

echo "✅ Python dependencies OK"
echo ""

# Build the Java project
echo "🔨 Building Java project..."
./gradlew build -q --console=plain

echo "✅ Build complete"
echo ""

# Start API server in background
echo "🚀 Starting REST API server on port 8080..."
./gradlew runApiServer > /tmp/clob-api.log 2>&1 &
API_PID=$!

echo "✅ API server started (PID: $API_PID)"
echo "   Logs: /tmp/clob-api.log"

# Wait for API to be ready
echo ""
echo "⏳ Waiting for API to be ready..."
for i in {1..30}; do
    if curl -s http://localhost:8080/health > /dev/null 2>&1; then
        echo "✅ API is ready!"
        break
    fi
    sleep 1
    if [ $i -eq 30 ]; then
        echo "❌ API failed to start. Check logs at /tmp/clob-api.log"
        kill $API_PID 2>/dev/null || true
        exit 1
    fi
done

echo ""
echo "🎨 Starting Streamlit UI..."
echo ""
echo "=========================================="
echo "  Press Ctrl+C to stop both servers"
echo "=========================================="
echo ""

# Cleanup function
cleanup() {
    echo ""
    echo "🛑 Stopping services..."
    kill $API_PID 2>/dev/null || true
    echo "✅ Services stopped"
    exit 0
}

trap cleanup INT TERM

# Start Streamlit
cd ui
streamlit run streamlit_app.py

# If Streamlit exits, cleanup
cleanup
