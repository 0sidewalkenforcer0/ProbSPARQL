package org.apache.jena.sparql.engine.iterator;

import org.apache.jena.atlas.io.IndentedWriter;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingBuilder;
import org.apache.jena.sparql.engine.main.QC;
import org.apache.jena.sparql.serializer.SerializationContext;
import org.apache.jena.graph.Node;
import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.probsparql.datatypes.GMMValue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * QueryIterSimilarityJoin: Iterator for SimilarityJoin operation with nested loop join semantics.
 * 
 * Relational semantics:
 * { leftPattern } SIMILARITYJOIN(?leftVar, ?rightVar, tolerance, tailProbability) { rightPattern }
 * 
 * Algorithm (Nested Loop Join):
 * 1. Materialize left table bindings
 * 2. Materialize right table bindings
 * 3. For each pair (leftBinding, rightBinding):
 *    - Merge bindings on common variables
 *    - Extract GMM from ?leftVar and ?rightVar
 *    - Compute JS divergence
 *    - If JS <= tolerance:
 *      - Yield the merged binding (NO fusion - keeps original variables)
 * 
 * Unlike FuseJoin, this does NOT create fused distributions - it keeps original variables.
 */
public class QueryIterSimilarityJoin extends QueryIter {
    private final Var leftVar;
    private final Var rightVar;
    private final double tolerance;
    private final double tailProbability;
    private final ExecutionContext execCxt;
    
    // Materialized tables
    private final List<Binding> leftTable;
    private final List<Binding> rightTable;
    
    // Join state
    private int leftIndex = 0;
    private int rightIndex = 0;
    private Binding nextBinding = null;
    
    /**
     * Constructor for nested loop SimilarityJoin.
     * 
     * @param leftInput   Iterator over left table bindings
     * @param rightOp     Operation to execute for right table
     * @param leftVar     Variable containing GMM in left table
     * @param rightVar    Variable containing GMM in right table
     * @param tolerance   JS divergence threshold
     * @param tailProbability One-sided tail probability for V3/V5 sequential bounds
     * @param execCxt     Execution context
     */
    public QueryIterSimilarityJoin(QueryIterator leftInput, Op rightOp,
                                   Var leftVar, Var rightVar, double tolerance,
                                   double tailProbability,
                                   ExecutionContext execCxt) {
        super(execCxt);
        
        // Validate tolerance parameter
        if (tolerance < 0) {
            throw new IllegalArgumentException(
                "Tolerance must be non-negative, got: " + tolerance);
        }
        
        this.leftVar = leftVar;
        this.rightVar = rightVar;
        this.tolerance = tolerance;
        this.tailProbability = tailProbability;
        this.execCxt = execCxt;
        
        // Materialize left table
        this.leftTable = materializeBindings(leftInput);
        
        // Execute and materialize right table
        // Use root binding for right table execution (no variable substitution from left)
        QueryIterator rightInput = QC.execute(rightOp, QueryIterRoot.create(execCxt), execCxt);
        this.rightTable = materializeBindings(rightInput);
        
    }
    
    /**
     * Materialize an iterator into a list of bindings.
     */
    private List<Binding> materializeBindings(QueryIterator iter) {
        List<Binding> bindings = new ArrayList<>();
        try {
            while (iter.hasNext()) {
                bindings.add(iter.next());
            }
        } finally {
            iter.close();
        }
        return bindings;
    }

    @Override
    protected boolean hasNextBinding() {
        if (nextBinding != null) {
            return true;
        }
        
        nextBinding = findNextBinding();
        return nextBinding != null;
    }

    @Override
    protected Binding moveToNextBinding() {
        Binding result = nextBinding;
        nextBinding = null;
        return result;
    }
    
    /**
     * Nested loop join algorithm.
     * For each (left, right) pair, check JS divergence and return merged binding if within tolerance.
     */
    private Binding findNextBinding() {
        while (leftIndex < leftTable.size()) {
            Binding leftBinding = leftTable.get(leftIndex);
            
            while (rightIndex < rightTable.size()) {
                Binding rightBinding = rightTable.get(rightIndex);
                rightIndex++;
                
                // Try to merge bindings
                Binding merged = tryMerge(leftBinding, rightBinding);
                if (merged == null) {
                    continue;
                }
                
                // Check if merged binding has both distribution variables
                Node leftNode = merged.get(leftVar);
                Node rightNode = merged.get(rightVar);
                
                if (leftNode == null || rightNode == null) {
                    continue;
                }
                
                // Validate GMM types
                if (!isValidGMM(leftNode) || !isValidGMM(rightNode)) {
                    continue;
                }
                
                GMMValue leftGMM = (GMMValue) leftNode.getLiteralValue();
                GMMValue rightGMM = (GMMValue) rightNode.getLiteralValue();
                
                // Check dimensionality compatibility
                if (leftGMM.getDimensions() != rightGMM.getDimensions()) {
                    continue;
                }
                
                // Compute JS divergence
                try {
                    double jsDiv = ProbSPARQL.evaluateSimilarity(leftNode, rightNode, tolerance, tailProbability);
                    
                    if (jsDiv <= tolerance) {
                        // Return the merged binding (no fusion)
                        return merged;
                    }
                } catch (Exception e) {
                    // Skip pairs where JS divergence computation fails
                    continue;
                }
            }
            
            // Move to next left binding, reset right index
            leftIndex++;
            rightIndex = 0;
        }

        return null; // No more matches
    }
    
    /**
     * Try to merge two bindings.
     * Returns null if there's a conflict (same variable bound to different values).
     */
    private Binding tryMerge(Binding left, Binding right) {
        BindingBuilder builder = BindingBuilder.create();
        
        // Add all from left
        Iterator<Var> leftVars = left.vars();
        while (leftVars.hasNext()) {
            Var v = leftVars.next();
            builder.add(v, left.get(v));
        }
        
        // Add from right, checking for conflicts
        Iterator<Var> rightVars = right.vars();
        while (rightVars.hasNext()) {
            Var v = rightVars.next();
            Node rightValue = right.get(v);
            
            if (left.contains(v)) {
                Node leftValue = left.get(v);
                // Both have this variable - must match
                if (!leftValue.equals(rightValue)) {
                    return null; // Conflict
                }
                // Same value - already in builder from left
            } else {
                // Only in right - add it
                builder.add(v, rightValue);
            }
        }
        
        return builder.build();
    }
    
    /**
     * Check if a node is a valid GMM literal.
     */
    private boolean isValidGMM(Node node) {
        if (node == null || !node.isLiteral()) {
            return false;
        }
        Object value = node.getLiteralValue();
        return value instanceof GMMValue;
    }

    @Override
    protected void closeIterator() {
        // Tables are already materialized, no iterators to close
    }

    @Override
    protected void requestCancel() {
        // No long-running operation to cancel
    }
    
    @Override
    public void output(IndentedWriter out, SerializationContext sCxt) {
        out.println("QueryIterSimilarityJoin (Nested Loop)");
        out.incIndent();
        out.println("Left Var: " + leftVar);
        out.println("Right Var: " + rightVar);
        out.println("Tolerance: " + tolerance);
        out.println("Left table size: " + leftTable.size());
        out.println("Right table size: " + rightTable.size());
        out.decIndent();
    }
}
