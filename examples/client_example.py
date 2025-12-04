#!/usr/bin/env python3
"""
ProbSPARQL Fuseki Client Example

This script demonstrates how to query the ProbSPARQL Fuseki endpoint
from Python using the SPARQLWrapper library.

Install dependencies:
    pip install SPARQLWrapper

Usage:
    python client_example.py
"""

from SPARQLWrapper import SPARQLWrapper, JSON
import json

# Configure SPARQL endpoint
ENDPOINT = "http://localhost:3030/probsparql/query"

def query_probsparql(sparql_query):
    """Execute a SPARQL query and return results"""
    sparql = SPARQLWrapper(ENDPOINT)
    sparql.setQuery(sparql_query)
    sparql.setReturnFormat(JSON)
    
    try:
        results = sparql.query().convert()
        return results
    except Exception as e:
        print(f"Error querying endpoint: {e}")
        return None

def example1_mean_values():
    """Example 1: Extract mean values using prob:mean()"""
    print("=" * 70)
    print("Example 1: Extract Mean Values")
    print("=" * 70)
    
    query = """
    PREFIX prob: <http://probsparql.org/function#>
    PREFIX uq: <http://example.org/ontology/uncertainty#>
    
    SELECT ?rv ?mean ?std WHERE {
      ?rv uq:hasDistribution ?dist .
      BIND(prob:mean(?dist) AS ?mean)
      BIND(prob:std(?dist) AS ?std)
    }
    LIMIT 5
    """
    
    results = query_probsparql(query)
    if results:
        for result in results["results"]["bindings"]:
            rv = result["rv"]["value"].split("/")[-1]
            mean = result["mean"]["value"]
            std = result["std"]["value"]
            print(f"  {rv}: μ={mean}, σ={std}")
    print()

def example2_worn_gears():
    """Example 2: U1 - Detect worn gears using prob:cdf()"""
    print("=" * 70)
    print("Example 2: Detect Worn Gears (U1)")
    print("=" * 70)
    
    query = """
    PREFIX prob: <http://probsparql.org/function#>
    PREFIX uq: <http://example.org/ontology/uncertainty#>
    PREFIX cfm: <http://example.org/ontology/cfm#>
    PREFIX ag: <http://example.org/ontology/anglegrinder#>
    PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
    PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
    
    SELECT ?gearLabel ?wornProb WHERE {
      ?gear a ag:Gear ;
            rdfs:label ?gearLabel .
      ?measure cfm:measuresCharacteristic ?char ;
               cfm:hasProbabilisticValue ?rv .
      ?char a ag:ToothWidth ;
            cfm:characterizes ?gear .
      ?rv uq:hasDistribution ?dist .
      BIND(prob:cdf(?dist, "8.8"^^xsd:double) AS ?wornProb)
      FILTER(?wornProb > 0.95)
    }
    ORDER BY DESC(?wornProb)
    LIMIT 5
    """
    
    results = query_probsparql(query)
    if results:
        for result in results["results"]["bindings"]:
            gear = result["gearLabel"]["value"]
            prob = float(result["wornProb"]["value"])
            print(f"  {gear}: {prob*100:.1f}% worn probability")
    print()

def example3_sensor_fusion():
    """Example 3: U4 - Sensor fusion with fuzzyJoin"""
    print("=" * 70)
    print("Example 3: Sensor Fusion with Fuzzy Join (U4)")
    print("=" * 70)
    
    query = """
    PREFIX prob: <http://probsparql.org/function#>
    PREFIX probpf: <http://probsparql.org/property#>
    PREFIX uq: <http://example.org/ontology/uncertainty#>
    PREFIX cfm: <http://example.org/ontology/cfm#>
    PREFIX ag: <http://example.org/ontology/anglegrinder#>
    PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
    PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
    
    SELECT ?spindleLabel ?mapDiameter ?meanDiameter ?stdDev WHERE {
      ?spindle a ag:Spindle ;
               rdfs:label ?spindleLabel ;
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
      
      BIND(prob:map(?fusedDist) AS ?mapJson)
      BIND(prob:mean(?fusedDist) AS ?meanJson)
      BIND(prob:std(?fusedDist) AS ?stdJson)
      
      BIND(xsd:double(REPLACE(?mapJson, "[\\\\[\\\\]]", "")) AS ?mapDiameter)
      BIND(xsd:double(REPLACE(?meanJson, "[\\\\[\\\\]]", "")) AS ?meanDiameter)
      BIND(xsd:double(REPLACE(?stdJson, "[\\\\[\\\\]]", "")) AS ?stdDev)
    }
    ORDER BY ?spindleLabel
    """
    
    results = query_probsparql(query)
    if results:
        for result in results["results"]["bindings"]:
            spindle = result["spindleLabel"]["value"]
            map_val = float(result["mapDiameter"]["value"])
            mean_val = float(result["meanDiameter"]["value"])
            std_val = float(result["stdDev"]["value"])
            print(f"  {spindle}:")
            print(f"    MAP:  {map_val:.4f} mm (most likely)")
            print(f"    Mean: {mean_val:.4f} mm (expected)")
            print(f"    Std:  {std_val:.4f} mm (uncertainty)")
            print(f"    Δ:    {abs(map_val - mean_val):.4f} mm (MAP-Mean difference)")
    print()

def example4_sensor_comparison():
    """Example 4: U2 - Compare sensors using JS divergence"""
    print("=" * 70)
    print("Example 4: Sensor Comparison (U2)")
    print("=" * 70)
    
    query = """
    PREFIX prob: <http://probsparql.org/function#>
    PREFIX uq: <http://example.org/ontology/uncertainty#>
    PREFIX cfm: <http://example.org/ontology/cfm#>
    PREFIX ag: <http://example.org/ontology/anglegrinder#>
    PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
    
    SELECT ?gearLabel ?js WHERE {
      ?gear a ag:Gear ;
            rdfs:label ?gearLabel .
      
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
    ORDER BY DESC(?js)
    LIMIT 5
    """
    
    results = query_probsparql(query)
    if results:
        print("  Gears with significant sensor disagreement (JS > 0.2):")
        for result in results["results"]["bindings"]:
            gear = result["gearLabel"]["value"]
            js = float(result["js"]["value"])
            print(f"    {gear}: JS divergence = {js:.4f}")
    print()

def main():
    print()
    print("╔════════════════════════════════════════════════════════════════╗")
    print("║  ProbSPARQL Python Client Examples                            ║")
    print("╚════════════════════════════════════════════════════════════════╝")
    print()
    print(f"Endpoint: {ENDPOINT}")
    print()
    
    # Run all examples
    example1_mean_values()
    example2_worn_gears()
    example3_sensor_fusion()
    example4_sensor_comparison()
    
    print("=" * 70)
    print("All examples completed!")
    print("=" * 70)
    print()

if __name__ == "__main__":
    main()
