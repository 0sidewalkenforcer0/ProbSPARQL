package org.apache.jena.sparql.engine.iterator;

import org.apache.jena.atlas.io.IndentedWriter;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingBuilder;
import org.apache.jena.sparql.serializer.SerializationContext;
import org.apache.jena.graph.Node;
import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.probsparql.datatypes.GMMValue;

import java.util.Iterator;

/**
 * QueryIterFuseJoinFilter: Filter iterator for legacy FUSEJOIN syntax.
 * 
 * Legacy syntax: FUSEJOIN(?leftVar, ?rightVar, tolerance, ?resultVar) { }
 * - Both distribution variables are already bound in the input bindings
 * - This iterator filters bindings based on JS divergence and fuses distributions
 * 
 * For each input binding:
 * 1. Extract GMM values from leftVar and rightVar
 * 2. Compute JS divergence
 * 3. If JS <= tolerance:
 *    - Fuse the distributions using Bayesian fusion
 *    - Add resultVar to the binding
 *    - Pass the binding through
 * 
 * This is a filter-and-transform operation, NOT a join. All variables are already bound.
 */
public class QueryIterFuseJoinFilter extends QueryIter {
    private final QueryIterator input;
    private final Var leftVar;
    private final Var rightVar;
    private final double tolerance;
    private final Var resultVar;
    
    private Binding nextBinding = null;
    private int checkedCount = 0;
    private int passedCount = 0;
    
    /**
     * Constructor for filter-mode FuseJoin.
     * 
     * @param input     Iterator over bindings that already contain both distribution variables
     * @param leftVar   Variable containing GMM
     * @param rightVar  Variable containing GMM
     * @param tolerance JS divergence threshold
     * @param resultVar Variable to bind the fused distribution
     * @param execCxt   Execution context
     */
    public QueryIterFuseJoinFilter(QueryIterator input, Var leftVar, Var rightVar,
                                   double tolerance, Var resultVar, ExecutionContext execCxt) {
        super(execCxt);
        
        if (tolerance < 0) {
            throw new IllegalArgumentException("Tolerance must be non-negative, got: " + tolerance);
        }
        
        this.input = input;
        this.leftVar = leftVar;
        this.rightVar = rightVar;
        this.tolerance = tolerance;
        this.resultVar = resultVar;
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
            
            // Validate GMM types
            if (!isValidGMM(leftNode) || !isValidGMM(rightNode)) {
                continue;
            }
            
            GMMValue leftGMM = (GMMValue) leftNode.getLiteralValue();
            GMMValue rightGMM = (GMMValue) rightNode.getLiteralValue();
            
            // Check dimensionality compatibility
            if (leftGMM.getD() != rightGMM.getD()) {
                continue;
            }
            
            // Compute JS divergence
            try {
                double jsDiv = ProbSPARQL.JSDivergence(leftNode, rightNode);
                
                if (jsDiv <= tolerance) {
                    passedCount++;
                    
                    // Fuse the distributions
                    Node fusedNode = ProbSPARQL.Fuse(leftNode, rightNode);
                    
                    if (fusedNode == null) {
                        continue;
                    }
                    
                    // Create new binding with fused result
                    Binding result = addBinding(binding, resultVar, fusedNode);
                    return result;
                }
            } catch (Exception e) {
                // Skip bindings where computation fails
                continue;
            }
        }
        
        return null;
    }
    
    /**
     * Add a variable binding to an existing binding.
     */
    private Binding addBinding(Binding binding, Var var, Node value) {
        BindingBuilder builder = BindingBuilder.create();
        
        // Copy all existing bindings
        Iterator<Var> vars = binding.vars();
        while (vars.hasNext()) {
            Var v = vars.next();
            builder.add(v, binding.get(v));
        }
        
        // Add the new binding
        builder.add(var, value);
        
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
        out.println("QueryIterFuseJoinFilter (Legacy Filter Mode)");
        out.incIndent();
        out.println("Left Var: " + leftVar);
        out.println("Right Var: " + rightVar);
        out.println("Tolerance: " + tolerance);
        out.println("Result Var: " + resultVar);
        out.println("Checked: " + checkedCount);
        out.println("Passed: " + passedCount);
        out.decIndent();
    }
}

