/**
 * Probabilistic transformation operators for GMM manipulations.
 * 
 * <p>This package contains SPARQL functions for transforming probability
 * distributions through algebraic operations and structural changes.</p>
 * 
 * <h2>Functions</h2>
 * <ul>
 *   <li>{@link org.apache.jena.probsparql.functions.transformation.Scale} - 
 *       Scale GMM by constant: {@code prob:scale(?gmm, ?factor)}</li>
 *   <li>{@link org.apache.jena.probsparql.functions.transformation.Shift} - 
 *       Shift GMM by offset: {@code prob:shift(?gmm, ?offset)}</li>
 *   <li>{@link org.apache.jena.probsparql.functions.transformation.LinearTransform} - 
 *       Linear transformation: {@code prob:linearTransform(?gmm, ?a, ?b)}</li>
 *   <li>{@link org.apache.jena.probsparql.functions.transformation.Marginal} - 
 *       Marginal distribution: {@code prob:marginal(?gmm, ?dimension)}</li>
 *   <li>{@link org.apache.jena.probsparql.functions.transformation.Joint} - 
 *       Joint distribution: {@code prob:joint(?gmm1, ?gmm2)}</li>
 *   <li>{@link org.apache.jena.probsparql.functions.transformation.Convolve} - 
 *       Convolution (sum): {@code prob:convolve(?gmm1, ?gmm2)}</li>
 * </ul>
 * 
 * <h2>Usage Example</h2>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/function#&gt;
 * PREFIX uq: &lt;http://example.org/ontology/uncertainty#&gt;
 * 
 * SELECT ?combined WHERE {
 *   ?part1 uq:hasDistribution ?gmm1 .
 *   ?part2 uq:hasDistribution ?gmm2 .
 *   BIND(prob:convolve(?gmm1, ?gmm2) AS ?combined)
 * }
 * </pre>
 * 
 * @author ProbSPARQL Team
 * @version 1.0.0
 * @since 1.0.0
 */
package org.apache.jena.probsparql.functions.transformation;
