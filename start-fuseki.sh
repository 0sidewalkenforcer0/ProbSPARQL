#!/bin/bash

# ProbSPARQL Fuseki Server Startup Script
# 
# This script starts the ProbSPARQL Fuseki server with optional data preloading.
#
# Usage:
#   ./start-fuseki.sh [port] [datafile1] [datafile2] ...
#
# Examples:
#   # Start with default port 3030, no data
#   ./start-fuseki.sh
#
#   # Start on custom port
#   ./start-fuseki.sh 3040
#
#   # Start with preloaded angle grinder data
#   ./start-fuseki.sh 3030 examples/data/angle-grinder-instances.ttl examples/ontologies/angle-grinder-ontology.ttl

set -e

echo "╔════════════════════════════════════════════════════════════════╗"
echo "║  ProbSPARQL Fuseki Server Launcher                            ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""

# Check if Maven is available
if ! command -v mvn &> /dev/null; then
    echo "Error: Maven not found. Please install Maven first."
    exit 1
fi

# Build the project if needed
if [ ! -d "target/classes" ]; then
    echo "Building project..."
    mvn clean compile
    echo ""
fi

# Default port
PORT=${1:-3030}

# Data files (all arguments after first one)
DATA_FILES=""
if [ $# -gt 1 ]; then
    shift
    DATA_FILES="$@"
fi

echo "Configuration:"
echo "  Port: $PORT"
if [ -n "$DATA_FILES" ]; then
    echo "  Data files:"
    for file in $DATA_FILES; do
        echo "    - $file"
    done
else
    echo "  Data files: (none - empty dataset)"
fi
echo ""

# Start server using Maven exec
echo "Starting Fuseki server..."
echo ""

mvn exec:java \
    -Dexec.mainClass="org.apache.jena.probsparql.server.ProbSPARQLFuseki" \
    -Dexec.args="$PORT $DATA_FILES"
