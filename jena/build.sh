#!/bin/bash
# Build script for modified Jena with ProbSPARQL extensions
# This script sets the correct JAVA_HOME and builds the project

# Set JAVA_HOME to the correct Java 21 path
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.9/libexec/openjdk.jdk/Contents/Home

# Verify Java version
echo "Using Java:"
$JAVA_HOME/bin/java -version
echo ""

# Build Jena-ARQ (and dependencies)
echo "Building Jena-ARQ with ProbSPARQL extensions..."
mvn clean install -DskipTests "$@"

if [ $? -eq 0 ]; then
    echo ""
    echo "=========================================="
    echo "Build SUCCESS!"
    echo "=========================================="
    echo "Modified Jena JARs have been installed to your local Maven repository."
    echo "You can now build the main ProbSPARQL project."
else
    echo ""
    echo "=========================================="
    echo "Build FAILED!"
    echo "=========================================="
    exit 1
fi

