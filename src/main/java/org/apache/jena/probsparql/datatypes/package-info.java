/**
 * Custom RDF datatypes for probabilistic SPARQL extensions.
 * 
 * This package provides custom datatype implementations for representing
 * uncertainty and probabilistic data in RDF, specifically:
 * 
 * <ul>
 *   <li>{@link org.apache.jena.probsparql.datatypes.GMMDatatype} - 
 *       Gaussian Mixture Model datatype for attribute-level uncertainty</li>
 *   <li>{@link org.apache.jena.probsparql.datatypes.GMMValue} - 
 *       Value object representing a GMM with K components in d dimensions</li>
 * </ul>
 * 
 * <h2>Usage Example</h2>
 * <pre>
 * // Register the custom datatype
 * ProbSPARQL.init();
 * 
 * // Load RDF data containing GMM literals
 * Model model = RDFDataMgr.loadModel("data.ttl");
 * 
 * // Query with GMM literals
 * String query = "SELECT ?measurement ?uncertainty WHERE { ... }";
 * </pre>
 * 
 * @author ProbSPARQL Team
 * @version 1.0.0
 * @since 1.0.0
 */
package org.apache.jena.probsparql.datatypes;
