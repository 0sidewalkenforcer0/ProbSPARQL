/**
 * Probabilistic thresholding operators for evaluating probabilities.
 * 
 * <p>This package contains SPARQL functions for evaluating probability
 * densities, cumulative probabilities, and their logarithms.</p>
 * 
 * <h2>Functions</h2>
 * <ul>
 *   <li>{@link org.apache.jena.probsparql.functions.thresholding.PDF} - 
 *       Probability Density Function: {@code prob:pdf(?gmm, ?point)}</li>
 *   <li>{@link org.apache.jena.probsparql.functions.thresholding.CDF} - 
 *       Cumulative Distribution Function: {@code prob:cdf(?gmm, ?point)}</li>
 *   <li>{@link org.apache.jena.probsparql.functions.thresholding.LogPDF} - 
 *       Log Probability Density Function: {@code prob:logpdf(?gmm, ?point)}</li>
 *   <li>{@link org.apache.jena.probsparql.functions.thresholding.LogCDF} - 
 *       Log Cumulative Distribution Function: {@code prob:logcdf(?gmm, ?point)}</li>
 * </ul>
 * 
 * <h2>Usage Example</h2>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/function#&gt;
 * PREFIX uq: &lt;http://example.org/ontology/uncertainty#&gt;
 * 
 * SELECT ?sensor ?density WHERE {
 *   ?sensor uq:hasDistribution ?gmm .
 *   BIND(prob:pdf(?gmm, 6.0) AS ?density)
 *   FILTER(?density &gt; 0.5)
 * }
 * </pre>
 * 
 * @author ProbSPARQL Team
 * @version 1.0.0
 * @since 1.0.0
 */
package org.apache.jena.probsparql.functions.thresholding;
