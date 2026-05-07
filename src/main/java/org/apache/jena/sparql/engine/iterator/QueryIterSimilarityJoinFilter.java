package org.apache.jena.sparql.engine.iterator;

import org.apache.jena.atlas.io.IndentedWriter;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.serializer.SerializationContext;
import org.apache.jena.graph.Node;
import org.apache.jena.probsparql.ProbSPARQL;

/**
 * QueryIterSimilarityJoinFilter: Filter iterator for legacy DIVJOIN syntax.
 * 
 * Legacy syntax: DIVJOIN(?leftVar, ?rightVar, tolerance, tailProbability) { }
 * - Both distribution variables are already bound in the input bindings
 * - This iterator filters bindings based on JS divergence
 * 
 * For each input binding:
 * 1. Extract supported probabilistic literals from leftVar and rightVar
 * 2. Compute JS divergence
 * 3. If JS <= tolerance, pass the binding through
 * 
 * This is a filter operation, NOT a join. All variables are already bound.
 */
public class QueryIterSimilarityJoinFilter extends QueryIter {
    private final QueryIterator input;
    private final Var leftVar;
    private final Var rightVar;
    private final double tolerance;
    private final double tailProbability;
    
    private Binding nextBinding = null;
    private int checkedCount = 0;
    private int passedCount = 0;
    
    /**
     * Constructor for filter-mode SimilarityJoin.
     * 
     * @param input     Iterator over bindings that already contain both distribution variables
     * @param leftVar   Variable containing a supported distribution
     * @param rightVar  Variable containing a supported distribution
     * @param tolerance JS divergence threshold
     * @param tailProbability One-sided tail probability for V3/V5 sequential bounds
     * @param execCxt   Execution context
     */
    public QueryIterSimilarityJoinFilter(QueryIterator input, Var leftVar, Var rightVar,
                                          double tolerance, double tailProbability, ExecutionContext execCxt) {
        super(execCxt);
        
        if (tolerance < 0) {
            throw new IllegalArgumentException("Tolerance must be non-negative, got: " + tolerance);
        }
        
        this.input = input;
        this.leftVar = leftVar;
        this.rightVar = rightVar;
        this.tolerance = tolerance;
        this.tailProbability = tailProbability;
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
     * Find the next binding that passes the JS divergence filter.
     */
    private Binding findNextBinding() {
        while (input.hasNext()) {
            Binding binding = input.next();
            checkedCount++;
            
            // Get distribution nodes from the binding
            Node leftNode = binding.get(leftVar);
            Node rightNode = binding.get(rightVar);
            
            if (leftNode == null || rightNode == null) {
                continue;
            }
            
            if (!ProbSPARQL.supportsSimilarityLiteral(leftNode)
                || !ProbSPARQL.supportsSimilarityLiteral(rightNode)) {
                continue;
            }

            // Compute JS divergence
            try {
                double jsDiv = ProbSPARQL.evaluateSimilarity(leftNode, rightNode, tolerance, tailProbability);
                if (jsDiv <= tolerance) {
                    passedCount++;
                    return binding;
                }
            } catch (Exception e) {
                continue;
            }
        }
        
        return null;
    }
    
    @Override
    protected void closeIterator() {
        if (input != null) {
            input.close();
        }
    }

    @Override
    protected void requestCancel() {
        if (input != null) {
            input.cancel();
        }
    }
    
    @Override
    public void output(IndentedWriter out, SerializationContext sCxt) {
        out.println("QueryIterSimilarityJoinFilter (Legacy Filter Mode)");
        out.incIndent();
        out.println("Left Var: " + leftVar);
        out.println("Right Var: " + rightVar);
        out.println("Tolerance: " + tolerance);
        out.println("Tail Probability: " + tailProbability);
        out.println("Checked: " + checkedCount);
        out.println("Passed: " + passedCount);
        out.decIndent();
    }
}
