#!/bin/zsh
#
# ProbSPARQL Query Runner
# Runs maintained U1-U5 example queries against the sample data
#
# Usage:
#   ./run_all_queries.sh           # Run maintained queries (U1-U5)
#   ./run_all_queries.sh U1        # Run only U1
#   ./run_all_queries.sh U1 U3 U5  # Run U1, U3, and U5
#

# Don't exit on error - we want to continue running queries
# set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Get the script directory
SCRIPT_DIR="${0:A:h}"
PROJECT_ROOT="${SCRIPT_DIR}/../.."
DATA_FILE="$PROJECT_ROOT/examples/data/angle-grinder-instances.ttl"

get_query_file() {
    case $1 in
        U1) echo "U1_probabilistic_thresholding.sparql" ;;
        U2) echo "U2_probabilistic_comparison.sparql" ;;
        U3) echo "U3_distribution_transformation.sparql" ;;
        U4) echo "U4_distribution_manipulation.sparql" ;;
        U5) echo "U5_similarityjoin_test.sparql" ;;
        U6) echo "U6_fusejoin_comparison.sparql" ;;
        *) echo "" ;;
    esac
}

get_description() {
    case $1 in
        U1) echo "Probabilistic Thresholding (PDF/CDF evaluation)" ;;
        U2) echo "Probabilistic Comparison (KL/JS divergence)" ;;
        U3) echo "Distribution Transformation (scale, shift, linear)" ;;
        U4) echo "Distribution Manipulation (mean, std, fuse)" ;;
        U5) echo "DIVJOIN (similarity filtering)" ;;
        U6) echo "Experimental FUSEJOIN (legacy Bayesian fusion)" ;;
        *) echo "Unknown" ;;
    esac
}

print_header() {
    echo ""
    echo -e "${BLUE}╔════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║${NC}  ${GREEN}ProbSPARQL Query Runner${NC}                                       ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}  Running maintained example queries U1-U5                      ${BLUE}║${NC}"
    echo -e "${BLUE}╚════════════════════════════════════════════════════════════════╝${NC}"
    echo ""
}

print_query_header() {
    local query_id=$1
    local description=${DESCRIPTIONS[$query_id]}
    echo ""
    echo -e "${YELLOW}────────────────────────────────────────────────────────────────${NC}"
    echo -e "${GREEN}▶ $query_id: $description${NC}"
    echo -e "${YELLOW}────────────────────────────────────────────────────────────────${NC}"
}

run_query() {
    local query_id=$1
    local query_file="${SCRIPT_DIR}/$(get_query_file $query_id)"
    local description=$(get_description $query_id)
    
    if [[ ! -f "$query_file" ]]; then
        echo -e "${RED}✗ Query file not found: $query_file${NC}"
        return 1
    fi
    
    # Print header
    echo ""
    echo -e "${YELLOW}────────────────────────────────────────────────────────────────${NC}"
    echo -e "${GREEN}▶ $query_id: $description${NC}"
    echo -e "${YELLOW}────────────────────────────────────────────────────────────────${NC}"
    
    echo -e "${BLUE}Query file:${NC} $(get_query_file $query_id)"
    echo ""
    
    # Run the query using Maven
    cd "$PROJECT_ROOT"
    mvn -q exec:java \
        -Dexec.mainClass="org.apache.jena.probsparql.QueryRunner" \
        -Dexec.args="$DATA_FILE $query_file" 2>&1 | grep -v "^\[" || true
    
    echo ""
    echo -e "${GREEN}✓ $query_id completed${NC}"
}

# Main execution
print_header

# Check if data file exists
if [[ ! -f "$DATA_FILE" ]]; then
    echo -e "${RED}✗ Data file not found: $DATA_FILE${NC}"
    echo "  Please ensure the sample data is available."
    exit 1
fi

echo -e "${BLUE}Data file:${NC} $DATA_FILE"
echo -e "${BLUE}Project root:${NC} $PROJECT_ROOT"

# Determine which queries to run
if [[ $# -eq 0 ]]; then
    # Run maintained README examples. U6 remains available explicitly as an
    # experimental legacy query but is not part of the default smoke test.
    QUERIES_TO_RUN=(U1 U2 U3 U4 U5)
else
    # Run specified queries
    QUERIES_TO_RUN=("$@")
fi

echo ""
echo -e "${BLUE}Queries to run:${NC} ${QUERIES_TO_RUN[*]}"

# Track results
PASSED=0
FAILED=0

# Run each query
for query_id in "${QUERIES_TO_RUN[@]}"; do
    # Validate query ID
    if [[ -z "$(get_query_file $query_id)" ]]; then
        echo -e "${RED}✗ Unknown query: $query_id${NC}"
        echo "  Valid queries: U1, U2, U3, U4, U5, U6"
        ((FAILED++))
        continue
    fi
    
    if run_query "$query_id"; then
        ((PASSED++))
    else
        ((FAILED++))
    fi
done

# Print summary
echo ""
echo -e "${BLUE}════════════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}Summary:${NC}"
echo -e "  Passed: ${GREEN}$PASSED${NC}"
echo -e "  Failed: ${RED}$FAILED${NC}"
echo -e "${BLUE}════════════════════════════════════════════════════════════════${NC}"

if [[ $FAILED -gt 0 ]]; then
    exit 1
fi
