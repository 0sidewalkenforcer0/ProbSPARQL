/**
 * Custom SPARQL functions for probabilistic query operations.
 * 
 * <p>This package provides implementations of probabilistic operators
 * organized into four categories for working with GMM distributions in SPARQL queries.</p>
 * 
 * <h2>Function Categories</h2>
 * 
 * <h3>1. Thresholding Operators ({@link org.apache.jena.probsparql.functions.thresholding})</h3>
 * <p>Evaluate probabilities at specific points or ranges:</p>
 * <ul>
 *   <li>{@code prob:pdf(?gmm, ?point)} - Probability Density Function</li>
 *   <li>{@code prob:cdf(?gmm, ?point)} - Cumulative Distribution Function (planned)</li>
 *   <li>{@code prob:confidence(?gmm, ?lower, ?upper)} - Confidence interval (planned)</li>
 * </ul>
 * 
 * <h3>2. Comparison Operators ({@link org.apache.jena.probsparql.functions.comparison})</h3>
 * <p>Compare and match probability distributions:</p>
 * <ul>
 *   <li>{@code prob:kl_divergence(?gmm1, ?gmm2)} - Kullback-Leibler divergence (planned)</li>
 *   <li>{@code prob:wasserstein(?gmm1, ?gmm2)} - Wasserstein distance (planned)</li>
 *   <li>{@code prob:hellinger(?gmm1, ?gmm2)} - Hellinger distance (planned)</li>
 * </ul>
 * 
 * <h3>3. Transformation Operators ({@link org.apache.jena.probsparql.functions.transformation})</h3>
 * <p>Transform and propagate distributions:</p>
 * <ul>
 *   <li>{@code prob:convolve(?gmm1, ?gmm2)} - Convolution (planned)</li>
 *   <li>{@code prob:marginalize(?gmm, ?dims)} - Marginalization (planned)</li>
 *   <li>{@code prob:scale(?gmm, ?factor)} - Scaling (planned)</li>
 * </ul>
 * 
 * <h3>4. Manipulation Operators ({@link org.apache.jena.probsparql.functions.manipulation})</h3>
 * <p>Manipulate and combine distributions:</p>
 * <ul>
 *   <li>{@code prob:fuse(?gmm1, ?gmm2)} - Bayesian fusion (planned)</li>
 *   <li>{@code prob:condition(?gmm, ?evidence)} - Conditioning (planned)</li>
 *   <li>{@code prob:mixture(?gmm1, ?gmm2, ?weight)} - Mixture (planned)</li>
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
 *   FILTER(?density &gt; 0.1)
 * }
 * </pre>
 * 
 * @author ProbSPARQL Team
 * @version 1.0.0
 * @since 1.0.0
 */
package org.apache.jena.probsparql.functions;
