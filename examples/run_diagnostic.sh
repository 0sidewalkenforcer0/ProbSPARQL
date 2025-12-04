#!/bin/bash

# Script to run diagnostic queries and save outputs for analysis

cd "$(dirname "$0")/.."

echo "================================================================"
echo "Diagnostic Test: U5 vs U6 Comparison"
echo "================================================================"
echo ""

# Run U7 diagnostic query
echo "Running U7 (JS Divergence Diagnostic)..."
mvn exec:java -Dexec.mainClass="org.apache.jena.probsparql.QueryRunner" \
  -Dexec.args="examples/data/angle-grinder-instances.ttl examples/queries/U7_diagnostic_js_divergence.sparql" \
  -q > /tmp/u7_js_diagnostic.txt 2>&1

echo "U7 output saved to /tmp/u7_js_diagnostic.txt"
echo ""

# Run U5
echo "Running U5 (SIMILARITYJOIN)..."
mvn exec:java -Dexec.mainClass="org.apache.jena.probsparql.QueryRunner" \
  -Dexec.args="examples/data/angle-grinder-instances.ttl examples/queries/U5_similarityjoin_test.sparql" \
  -q > /tmp/u5_similarityjoin.txt 2>&1

echo "U5 output saved to /tmp/u5_similarityjoin.txt"
echo ""

# Run U6
echo "Running U6 (FUSEJOIN)..."
mvn exec:java -Dexec.mainClass="org.apache.jena.probsparql.QueryRunner" \
  -Dexec.args="examples/data/angle-grinder-instances.ttl examples/queries/U6_fusejoin_comparison.sparql" \
  -q > /tmp/u6_fusejoin.txt 2>&1

echo "U6 output saved to /tmp/u6_fusejoin.txt"
echo ""

# Extract spindle counts
echo "================================================================"
echo "Analysis Results"
echo "================================================================"
echo ""

echo "U7 - JS Divergence Values:"
grep -E "(spindle_00[0-9]|jsDivergence|toleranceCheck)" /tmp/u7_js_diagnostic.txt | head -20

echo ""
echo "U5 - SIMILARITYJOIN Spindle Count:"
grep -o "spindle_00[0-9]" /tmp/u5_similarityjoin.txt | sort -u | wc -l
grep -o "spindle_00[0-9]" /tmp/u5_similarityjoin.txt | sort -u

echo ""
echo "U6 - FUSEJOIN Spindle Count:"
grep -o "spindle_00[0-9]" /tmp/u6_fusejoin.txt | sort -u | wc -l
grep -o "spindle_00[0-9]" /tmp/u6_fusejoin.txt | sort -u

echo ""
echo "Full outputs saved in /tmp/u{5,6,7}_*.txt"
