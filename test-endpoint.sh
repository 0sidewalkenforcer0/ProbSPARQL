#!/bin/bash

# Test ProbSPARQL Fuseki Endpoint
# This script tests the HTTP SPARQL endpoint with various ProbSPARQL queries

ENDPOINT="http://localhost:3030/probsparql/query"

echo "╔════════════════════════════════════════════════════════════════╗"
echo "║  Testing ProbSPARQL Fuseki HTTP Endpoint                      ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""

# Test 1: Simple query with prob:mean
echo "Test 1: Extract mean values using prob:mean()"
echo "────────────────────────────────────────────────────────────────"
curl -s -X POST "$ENDPOINT" \
  -H "Content-Type: application/sparql-query" \
  -H "Accept: application/sparql-results+json" \
  --data-binary @- << 'EOF' | python3 -m json.tool
PREFIX prob: <http://probsparql.org/function#>
PREFIX uq: <http://example.org/ontology/uncertainty#>

SELECT ?rv ?mean WHERE {
  ?rv uq:hasDistribution ?dist .
  BIND(prob:mean(?dist) AS ?mean)
}
LIMIT 5
EOF

echo ""
echo ""

# Test 2: U1 Probabilistic Thresholding
echo "Test 2: U1 - Detect worn gears (prob:cdf)"
echo "────────────────────────────────────────────────────────────────"
curl -s -X POST "$ENDPOINT" \
  -H "Content-Type: application/sparql-query" \
  -H "Accept: application/sparql-results+json" \
  --data-binary @- << 'EOF' | python3 -m json.tool
PREFIX prob: <http://probsparql.org/function#>
PREFIX uq: <http://example.org/ontology/uncertainty#>
PREFIX cfm: <http://example.org/ontology/cfm#>
PREFIX ag: <http://example.org/ontology/anglegrinder#>
PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>

SELECT ?gear ?wornProb WHERE {
  ?gear a ag:Gear .
  ?measure cfm:measuresCharacteristic ?char ;
           cfm:hasProbabilisticValue ?rv .
  ?char a ag:ToothWidth .
  ?rv uq:hasDistribution ?dist .
  BIND(prob:cdf(?dist, "8.8"^^xsd:double) AS ?wornProb)
  FILTER(?wornProb > 0.95)
}
LIMIT 3
EOF

echo ""
echo ""

# Test 3: U2 Sensor Comparison
echo "Test 3: U2 - Compare sensors (prob:jsdivergence)"
echo "────────────────────────────────────────────────────────────────"
curl -s -X POST "$ENDPOINT" \
  -H "Content-Type: application/sparql-query" \
  -H "Accept: application/sparql-results+json" \
  --data-binary @- << 'EOF' | python3 -m json.tool
PREFIX prob: <http://probsparql.org/function#>
PREFIX uq: <http://example.org/ontology/uncertainty#>
PREFIX cfm: <http://example.org/ontology/cfm#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?gear ?js WHERE {
  ?ctMeasure cfm:measuresCharacteristic ?char ;
             rdfs:label ?ctLabel ;
             cfm:hasProbabilisticValue ?rvCT .
  FILTER(CONTAINS(?ctLabel, "CT"))
  
  ?slMeasure cfm:measuresCharacteristic ?char ;
             rdfs:label ?slLabel ;
             cfm:hasProbabilisticValue ?rvSL .
  FILTER(CONTAINS(?slLabel, "SL"))
  
  ?char cfm:characterizes ?gear .
  
  ?rvCT uq:hasDistribution ?ctDist .
  ?rvSL uq:hasDistribution ?slDist .
  
  BIND(prob:jsdivergence(?ctDist, ?slDist) AS ?js)
  FILTER(?js > 0.2)
}
LIMIT 3
EOF

echo ""
echo ""

# Test 4: Distribution Manipulation
echo "Test 4: U4 - Sensor fusion (prob:fuse + prob:map)"
echo "────────────────────────────────────────────────────────────────"
curl -s -X POST "$ENDPOINT" \
  -H "Content-Type: application/sparql-query" \
  -H "Accept: application/sparql-results+json" \
  --data-binary @- << 'EOF' | python3 -m json.tool
PREFIX prob: <http://probsparql.org/function#>
PREFIX uq: <http://example.org/ontology/uncertainty#>
PREFIX cfm: <http://example.org/ontology/cfm#>
PREFIX ag: <http://example.org/ontology/anglegrinder#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?spindle ?map ?mean WHERE {
  ?spindle a ag:Spindle ;
           ag:hasDiameterCharacteristic ?diameterChar .
  
  ?caliperMeasure cfm:measuresCharacteristic ?diameterChar ;
                  rdfs:label ?caliperLabel ;
                  cfm:hasProbabilisticValue ?rvCaliper .
  FILTER(CONTAINS(?caliperLabel, "Caliper"))
  
  ?laserMeasure cfm:measuresCharacteristic ?diameterChar ;
                rdfs:label ?laserLabel ;
                cfm:hasProbabilisticValue ?rvLaser .
  FILTER(CONTAINS(?laserLabel, "Laser"))
  
  ?rvCaliper uq:hasDistribution ?caliperDist .
  ?rvLaser uq:hasDistribution ?laserDist .
  
  BIND(prob:fuse(?caliperDist, ?laserDist) AS ?fusedDist)
  BIND(prob:map(?fusedDist) AS ?map)
  BIND(prob:mean(?fusedDist) AS ?mean)
}
LIMIT 2
EOF

echo ""
echo ""

# Test 5: Property Function - Fuzzy Join
echo "Test 5: Fuzzy Join Property Function"
echo "────────────────────────────────────────────────────────────────"
curl -s -X POST "$ENDPOINT" \
  -H "Content-Type: application/sparql-query" \
  -H "Accept: application/sparql-results+json" \
  --data-binary @- << 'EOF' | python3 -m json.tool
PREFIX prob: <http://probsparql.org/function#>
PREFIX probpf: <http://probsparql.org/property#>
PREFIX uq: <http://example.org/ontology/uncertainty#>
PREFIX cfm: <http://example.org/ontology/cfm#>
PREFIX ag: <http://example.org/ontology/anglegrinder#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?spindle ?map WHERE {
  ?spindle a ag:Spindle ;
           ag:hasDiameterCharacteristic ?diameterChar .
  
  ?caliperMeasure cfm:measuresCharacteristic ?diameterChar ;
                  rdfs:label ?caliperLabel ;
                  cfm:hasProbabilisticValue ?rvCaliper .
  FILTER(CONTAINS(?caliperLabel, "Caliper"))
  
  ?laserMeasure cfm:measuresCharacteristic ?diameterChar ;
                rdfs:label ?laserLabel ;
                cfm:hasProbabilisticValue ?rvLaser .
  FILTER(CONTAINS(?laserLabel, "Laser"))
  
  ?rvCaliper uq:hasDistribution ?caliperDist .
  ?rvLaser uq:hasDistribution ?laserDist .
  
  (?caliperMeasure ?laserMeasure) probpf:fuzzyJoin (?caliperDist ?laserDist 0.3 ?fusedDist) .
  
  BIND(prob:map(?fusedDist) AS ?map)
}
LIMIT 2
EOF

echo ""
echo ""
echo "════════════════════════════════════════════════════════════════"
echo "All tests completed!"
echo ""
echo "Server is running at: http://localhost:3030/"
echo "Query endpoint: http://localhost:3030/probsparql/query"
echo "════════════════════════════════════════════════════════════════"
