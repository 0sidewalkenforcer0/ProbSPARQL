/**
 * Probabilistic comparison operators for measuring distribution similarity.
 * 
 * <p>This package contains SPARQL functions for computing distances and
 * divergences between probability distributions represented as GMMs.</p>
 * 
 * <h2>Functions</h2>
 * <ul>
 *   <li>{@link org.apache.jena.probsparql.functions.comparison.KLDivergence} - 
 *       Kullback-Leibler Divergence: {@code prob:kldivergence(?gmm1, ?gmm2)}</li>
 *   <li>{@link org.apache.jena.probsparql.functions.comparison.JSDivergence} - 
 *       Jensen-Shannon Divergence: {@code prob:jsdivergence(?gmm1, ?gmm2)}</li>
 * </ul>
 * 
 * <h2>Planned Functions</h2>
 * <ul>
 *   <li>Wasserstein Distance - {@code prob:wasserstein(?gmm1, ?gmm2)}</li>
 *   <li>Hellinger Distance - {@code prob:hellinger(?gmm1, ?gmm2)}</li>
 *   <li>Bhattacharyya Distance - {@code prob:bhattacharyya(?gmm1, ?gmm2)}</li>
 * </ul>
 * 
 * <h2>Usage Example</h2>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/function#&gt;
 * PREFIX uq: &lt;http://example.org/ontology/uncertainty#&gt;
 * 
 * SELECT ?sensor1 ?sensor2 ?divergence WHERE {
 *   ?sensor1 uq:hasDistribution ?gmm1 .
 *   ?sensor2 uq:hasDistribution ?gmm2 .
 *   BIND(prob:kldivergence(?gmm1, ?gmm2) AS ?divergence)
 *   FILTER(?divergence &lt; 0.1)
 * }
 * </pre>
 * 
 * @author ProbSPARQL Team
 * @version 1.0.0
 * @since 1.0.0
 */
package org.apache.jena.probsparql.functions.comparison;
