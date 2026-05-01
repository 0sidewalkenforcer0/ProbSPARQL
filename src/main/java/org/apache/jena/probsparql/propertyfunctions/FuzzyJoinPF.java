package org.apache.jena.probsparql.propertyfunctions;

import org.apache.jena.graph.Node;
import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.probsparql.functions.manipulation.Fuse;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingFactory;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.pfunction.PropertyFunctionBase;
import org.apache.jena.sparql.pfunction.PropFuncArg;
import org.apache.jena.sparql.util.IterLib;

/**
 * Property function for fuzzy probabilistic join.
 * 
 * <p>Matches two GMMs if their JS divergence is below a tolerance threshold,
 * indicating they represent similar (but not necessarily identical) distributions.
 * Returns the Bayesian fusion of the two distributions.</p>
 * 
 * <p>Syntax:</p>
 * <pre>
 * (?subject1 ?subject2) prob:fuzzyJoin (?gmm1 ?gmm2 tolerance ?fusedGMM)
 * </pre>
 * 
 * <p>Where:</p>
 * <ul>
 *   <li>?subject1, ?subject2 - Entities being joined</li>
 *   <li>?gmm1, ?gmm2 - GMM distributions to compare</li>
 *   <li>tolerance - Maximum JS divergence for match (double, e.g., 0.05)</li>
 *   <li>?fusedGMM - Output variable for fused distribution</li>
 * </ul>
 * 
 * <p>Usage Example:</p>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/property#&gt;
 * PREFIX uq: &lt;http://example.org/ontology/uncertainty#&gt;
 * 
 * # Find sensors with similar measurements (JS &lt; 0.05)
 * SELECT ?sensor1 ?sensor2 ?combined WHERE {
 *   ?sensor1 uq:hasDistribution ?gmm1 .
 *   ?sensor2 uq:hasDistribution ?gmm2 .
 *   (?sensor1 ?sensor2) prob:fuzzyJoin (?gmm1 ?gmm2 0.05 ?combined) .
 * }
 * </pre>
 * 
 * <p>The tolerance parameter controls matching strictness:</p>
 * <ul>
 *   <li>0.01 - Very strict (nearly identical distributions)</li>
 *   <li>0.05 - Moderate (similar distributions)</li>
 *   <li>0.1  - Loose (somewhat similar distributions)</li>
 * </ul>
 * 
 * @author ProbSPARQL Team
 */
public class FuzzyJoinPF extends PropertyFunctionBase {
    
    public static final String URI = "http://probsparql.org/property#fuzzyJoin";
    
    /**
     * Execute the property function.
     * 
     * @param binding Current variable bindings
     * @param subject Subject argument (?subject1 ?subject2)
     * @param object Object argument (?gmm1 ?gmm2 tolerance ?fusedGMM)
     * @param execCxt Execution context
     * @return Query iterator with matched bindings
     */
    @Override
    public QueryIterator exec(Binding binding, PropFuncArg subject, Node predicate,
                              PropFuncArg object, ExecutionContext execCxt) {
        // Validate subject argument structure
        if (subject.getArgListSize() != 2) {
            throw new IllegalArgumentException(
                "prob:fuzzyJoin requires exactly 2 subject arguments: (?s1 ?s2), got " + 
                subject.getArgListSize());
        }
        
        // Validate object argument structure
        if (object.getArgListSize() != 4) {
            throw new IllegalArgumentException(
                "prob:fuzzyJoin requires exactly 4 arguments: (?gmm1 ?gmm2 tolerance ?fusedGMM), got " + 
                object.getArgListSize());
        }
        
        Node gmm1Node = object.getArg(0);
        Node gmm2Node = object.getArg(1);
        Node toleranceNode = object.getArg(2);
        Node fusedNode = object.getArg(3);
        
        // Early validation of tolerance parameter (before variable resolution)
        // This ensures we catch invalid literal tolerances early
        if (!toleranceNode.isVariable()) {
            double tolerance = extractTolerance(toleranceNode);
            if (tolerance < 0 || tolerance > 1) {
                throw new IllegalArgumentException(
                    "Tolerance must be in [0, 1], got: " + tolerance);
            }
        }
        
        // Get subject nodes
        Node subject1 = subject.getArg(0);
        Node subject2 = subject.getArg(1);
        
        // Resolve variables in subject
        if (subject1.isVariable()) {
            subject1 = binding.get(Var.alloc(subject1));
        }
        if (subject2.isVariable()) {
            subject2 = binding.get(Var.alloc(subject2));
        }
        
        // Prevent self-joins: subject1 must be different from subject2
        if (subject1 != null && subject2 != null && subject1.equals(subject2)) {
            return IterLib.noResults(execCxt);
        }
        
        // Get actual values from binding if variables
        if (gmm1Node.isVariable()) {
            gmm1Node = binding.get(Var.alloc(gmm1Node));
            if (gmm1Node == null) {
                return IterLib.noResults(execCxt);
            }
        }
        
        if (gmm2Node.isVariable()) {
            gmm2Node = binding.get(Var.alloc(gmm2Node));
            if (gmm2Node == null) {
                return IterLib.noResults(execCxt);
            }
        }
        
        if (toleranceNode.isVariable()) {
            toleranceNode = binding.get(Var.alloc(toleranceNode));
            if (toleranceNode == null) {
                return IterLib.noResults(execCxt);
            }
        }
        
        // Extract GMM values
        GMMValue gmm1 = extractGMM(gmm1Node, "first GMM");
        GMMValue gmm2 = extractGMM(gmm2Node, "second GMM");
        
        // Extract tolerance
        double tolerance = extractTolerance(toleranceNode);
        
        // Validate tolerance
        if (tolerance < 0 || tolerance > 1) {
            throw new IllegalArgumentException(
                "Tolerance must be in [0, 1], got: " + tolerance);
        }
        
        // Compute JS divergence
        double js = computeSimilarityScore(gmm1, gmm2, tolerance);
        
        // Check if distributions are similar enough
        if (js >= tolerance) {
            // No match - JS divergence too high
            return IterLib.noResults(execCxt);
        }
        
        // Distributions match! Perform fusion
        GMMValue fusedGMM = fuseTwoGMMs(gmm1, gmm2);
        
        // Create node for fused GMM
        Node fusedGMMNode = org.apache.jena.graph.NodeFactory.createLiteralDT(
            fusedGMM.toJSON(), GMMDatatype.INSTANCE
        );
        
        // Create new binding with fused result
        Binding newBinding;
        if (fusedNode.isVariable()) {
            Var fusedVar = Var.alloc(fusedNode);
            // Return single result with fused distribution bound to variable
            return IterLib.oneResult(binding, fusedVar, fusedGMMNode, execCxt);
        } else {
            // fusedNode is concrete - verify it matches
            if (!fusedNode.equals(fusedGMMNode)) {
                return IterLib.noResults(execCxt);
            }
            // Return existing binding
            return IterLib.result(binding, execCxt);
        }
    }
    
    /**
     * Extract GMMValue from a node.
     */
    private GMMValue extractGMM(Node node, String position) {
        if (!node.isLiteral()) {
            throw new IllegalArgumentException(
                "The " + position + " must be a GMM literal");
        }
        
        Object value = node.getLiteralValue();
        if (!(value instanceof GMMValue)) {
            throw new IllegalArgumentException(
                "The " + position + " must be of type " + GMMDatatype.URI);
        }
        
        return (GMMValue) value;
    }
    
    /**
     * Extract tolerance value from a node.
     */
    private double extractTolerance(Node node) {
        if (!node.isLiteral()) {
            throw new IllegalArgumentException(
                "Tolerance must be a numeric literal");
        }
        
        Object value = node.getLiteralValue();
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        
        // Try parsing as string
        String lexical = node.getLiteralLexicalForm();
        try {
            return Double.parseDouble(lexical);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Tolerance must be a number, got: " + lexical);
        }
    }
    
    /**
     * Compute JS divergence between two GMMs.
     */
    private double computeSimilarityScore(GMMValue gmm1, GMMValue gmm2, double tolerance) {
        // Create NodeValues for JSDivergence function
        Node node1 = org.apache.jena.graph.NodeFactory.createLiteralDT(
            gmm1.toJSON(), GMMDatatype.INSTANCE);
        Node node2 = org.apache.jena.graph.NodeFactory.createLiteralDT(
            gmm2.toJSON(), GMMDatatype.INSTANCE);

        return ProbSPARQL.evaluateSimilarity(node1, node2, tolerance);
    }
    
    /**
     * Fuse two GMMs using Bayesian fusion.
     */
    private GMMValue fuseTwoGMMs(GMMValue gmm1, GMMValue gmm2) {
        // Create NodeValues for Fuse function
        Node node1 = org.apache.jena.graph.NodeFactory.createLiteralDT(
            gmm1.toJSON(), GMMDatatype.INSTANCE);
        Node node2 = org.apache.jena.graph.NodeFactory.createLiteralDT(
            gmm2.toJSON(), GMMDatatype.INSTANCE);
        
        NodeValue nv1 = NodeValue.makeNode(node1);
        NodeValue nv2 = NodeValue.makeNode(node2);
        
        // Use Fuse function
        Fuse fuseFunc = new Fuse();
        NodeValue result = fuseFunc.exec(nv1, nv2);
        
        return (GMMValue) result.asNode().getLiteralValue();
    }
}
