/**
 * Property functions for probabilistic SPARQL queries.
 * 
 * <p>Property functions extend SPARQL with custom triple patterns that
 * perform specialized operations beyond standard graph matching.</p>
 * 
 * <h2>Probabilistic Join Functions</h2>
 * <ul>
 *   <li>{@code prob:exactJoin} - Join GMMs with JS divergence = 0</li>
 *   <li>{@code prob:fuzzyJoin} - Join GMMs with JS divergence &lt; tolerance</li>
 * </ul>
 * 
 * <h2>Usage Examples</h2>
 * 
 * <h3>Exact Join</h3>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/property#&gt;
 * PREFIX uq: &lt;http://example.org/ontology/uncertainty#&gt;
 * 
 * SELECT ?s1 ?s2 ?fused WHERE {
 *   ?s1 uq:hasDistribution ?gmm1 .
 *   ?s2 uq:hasDistribution ?gmm2 .
 *   (?s1 ?s2) prob:exactJoin (?gmm1 ?gmm2 ?fused) .
 * }
 * </pre>
 * 
 * <h3>Fuzzy Join</h3>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/property#&gt;
 * PREFIX uq: &lt;http://example.org/ontology/uncertainty#&gt;
 * 
 * # Find similar distributions (JS &lt; 0.05)
 * SELECT ?s1 ?s2 ?fused WHERE {
 *   ?s1 uq:hasDistribution ?gmm1 .
 *   ?s2 uq:hasDistribution ?gmm2 .
 *   (?s1 ?s2) prob:fuzzyJoin (?gmm1 ?gmm2 0.05 ?fused) .
 * }
 * </pre>
 * 
 * @author ProbSPARQL Team
 * @version 1.0.0
 * @since 1.0.0
 */
package org.apache.jena.probsparql.propertyfunctions;
