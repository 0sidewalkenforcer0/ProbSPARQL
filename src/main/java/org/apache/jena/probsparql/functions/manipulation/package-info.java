/**
 * Manipulation functions for GMM distributions in SPARQL queries.
 * 
 * <p>This package provides functions to manipulate, combine, and extract
 * information from Gaussian Mixture Model (GMM) distributions.</p>
 * 
 * <h2>Statistical Extraction Functions</h2>
 * <ul>
 *   <li>{@code prob:mean(?gmm)} - Compute expected value E[X]</li>
 *   <li>{@code prob:std(?gmm)} - Compute standard deviation (diagonal covariance)</li>
 *   <li>{@code prob:map(?gmm)} - Maximum A Posteriori estimate (mode)</li>
 *   <li>{@code prob:quantile(?gmm, ?q)} - Compute quantiles (1D only)</li>
 *   <li>{@code prob:modeCount(?gmm)} - Count number of components K</li>
 * </ul>
 * 
 * <h2>Distribution Combination Functions</h2>
 * <ul>
 *   <li>{@code prob:mix(?gmm1, ?gmm2, ?weight)} - Weighted mixture</li>
 *   <li>{@code prob:fuse(?gmm1, ?gmm2)} - Bayesian fusion (product)</li>
 * </ul>
 * 
 * <h2>Implemented Functions</h2>
 * <ul>
 *   <li>mean - Returns JSON string of mean vector</li>
 *   <li>std - Returns JSON string of standard deviations</li>
 *   <li>map - Returns JSON string of MAP estimate</li>
 *   <li>modeCount - Returns integer K</li>
 *   <li>mix - Returns new GMM (weighted mixture)</li>
 *   <li>fuse - Returns new GMM (Bayesian fusion)</li>
 *   <li>quantile - Returns double (1D quantile)</li>
 * </ul>
 * 
 * <h2>Usage Examples</h2>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/function#&gt;
 * PREFIX uq: &lt;http://example.org/ontology/uncertainty#&gt;
 * 
 * # Extract mean and standard deviation
 * SELECT ?meanValue ?stdValue WHERE {
 *   ?rv uq:hasDistribution ?gmm .
 *   BIND(prob:mean(?gmm) AS ?meanValue)
 *   BIND(prob:std(?gmm) AS ?stdValue)
 * }
 * 
 * # Bayesian fusion of two distributions
 * SELECT ?posterior WHERE {
 *   ?prior uq:hasDistribution ?priorGMM .
 *   ?likelihood uq:hasDistribution ?likelihoodGMM .
 *   BIND(prob:fuse(?priorGMM, ?likelihoodGMM) AS ?posterior)
 * }
 * 
 * # Compute 95th percentile
 * SELECT ?q95 WHERE {
 *   ?rv uq:hasDistribution ?gmm .
 *   BIND(prob:quantile(?gmm, 0.95) AS ?q95)
 * }
 * </pre>
 * 
 * @author ProbSPARQL Team
 * @version 1.0.0
 * @since 1.0.0
 */
package org.apache.jena.probsparql.functions.manipulation;
