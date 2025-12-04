/**
 * ProbSPARQL Fuseki Server - HTTP SPARQL Endpoint
 * 
 * This package provides an Apache Jena Fuseki server with ProbSPARQL extensions,
 * enabling HTTP access to probabilistic SPARQL queries.
 * 
 * <h2>Server Features</h2>
 * <ul>
 *   <li>HTTP SPARQL endpoint with all 22 ProbSPARQL functions</li>
 *   <li>Property functions for probabilistic joins (fuzzyJoin, exactJoin)</li>
 *   <li>Web-based query interface</li>
 *   <li>RESTful API for programmatic access</li>
 *   <li>In-memory or persistent TDB2 storage</li>
 * </ul>
 * 
 * <h2>Quick Start</h2>
 * <pre>
 * // Start server with default settings
 * java -cp jena-probsparql.jar org.apache.jena.probsparql.server.ProbSPARQLFuseki
 * 
 * // Start with preloaded data
 * java -cp jena-probsparql.jar org.apache.jena.probsparql.server.ProbSPARQLFuseki \
 *      3030 examples/data/angle-grinder-instances.ttl
 * </pre>
 * 
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>SPARQL Query: <code>http://localhost:3030/probsparql/query</code></li>
 *   <li>SPARQL Update: <code>http://localhost:3030/probsparql/update</code></li>
 *   <li>Web UI: <code>http://localhost:3030/</code></li>
 * </ul>
 * 
 * @see org.apache.jena.probsparql.server.ProbSPARQLFuseki
 * @author ProbSPARQL Team
 */
package org.apache.jena.probsparql.server;
