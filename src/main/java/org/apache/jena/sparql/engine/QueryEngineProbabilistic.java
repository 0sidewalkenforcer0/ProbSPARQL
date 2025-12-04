package org.apache.jena.sparql.engine;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.sparql.ARQConstants;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.op.OpFuseJoin;
import org.apache.jena.sparql.algebra.op.OpSimilarityJoin;
import org.apache.jena.sparql.algebra.Transformer;
import org.apache.jena.sparql.algebra.TransformCopy;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.main.OpExecutorProbabilistic;
import org.apache.jena.sparql.engine.main.QueryEngineMain;
import org.apache.jena.sparql.util.Context;

/**
 * Custom query engine for ProbSPARQL that handles FUSEJOIN and SIMILARITYJOIN operations.
 * 
 * Updated for new relational semantics:
 * { leftPattern } FUSEJOIN(?leftVar, ?rightVar, tolerance, ?resultVar) { rightPattern }
 * { leftPattern } SIMILARITYJOIN(?leftVar, ?rightVar, tolerance) { rightPattern }
 */
public class QueryEngineProbabilistic extends QueryEngineMain {
    
    // Store reference to original query for prefix mapping access
    private final Query originalQuery;
    
    public QueryEngineProbabilistic(Query query, DatasetGraph dataset, Binding input, Context context) {
        super(query, dataset, input, context);
        this.originalQuery = query;
        // Register our custom OpExecutor factory
        context.set(ARQConstants.sysOpExecutorFactory, OpExecutorProbabilistic.factory);
    }
    
    @Override
    protected Op modifyOp(Op op) {
        // Ensure OpExecutorProbabilistic is registered
        super.context.set(ARQConstants.sysOpExecutorFactory, OpExecutorProbabilistic.factory);
        
        // Check if we have FUSEJOIN metadata from context (new relational syntax)
        String fuseLeftPattern = (String) super.context.get(org.apache.jena.probsparql.ProbSPARQL.FUSEJOIN_LEFT_PATTERN);
        String fuseRightPattern = (String) super.context.get(org.apache.jena.probsparql.ProbSPARQL.FUSEJOIN_RIGHT_PATTERN);
        String fuseLeftVarStr = (String) super.context.get(org.apache.jena.probsparql.ProbSPARQL.FUSEJOIN_LEFT_VAR);
        String fuseRightVarStr = (String) super.context.get(org.apache.jena.probsparql.ProbSPARQL.FUSEJOIN_RIGHT_VAR);
        Double fuseTolerance = (Double) super.context.get(org.apache.jena.probsparql.ProbSPARQL.FUSEJOIN_TOLERANCE);
        String fuseResultVarStr = (String) super.context.get(org.apache.jena.probsparql.ProbSPARQL.FUSEJOIN_RESULT_VAR);
        
        // Convert String variable names to Var objects
        Var fuseLeftVar = fuseLeftVarStr != null ? Var.alloc(fuseLeftVarStr) : null;
        Var fuseRightVar = fuseRightVarStr != null ? Var.alloc(fuseRightVarStr) : null;
        Var fuseResultVar = fuseResultVarStr != null ? Var.alloc(fuseResultVarStr) : null;
        
        // Check if we have SIMILARITYJOIN metadata from context (new relational syntax)
        String simLeftPattern = (String) super.context.get(org.apache.jena.probsparql.ProbSPARQL.SIMILARITYJOIN_LEFT_PATTERN);
        String simRightPattern = (String) super.context.get(org.apache.jena.probsparql.ProbSPARQL.SIMILARITYJOIN_RIGHT_PATTERN);
        String simLeftVarStr = (String) super.context.get(org.apache.jena.probsparql.ProbSPARQL.SIMILARITYJOIN_LEFT_VAR);
        String simRightVarStr = (String) super.context.get(org.apache.jena.probsparql.ProbSPARQL.SIMILARITYJOIN_RIGHT_VAR);
        Double simTolerance = (Double) super.context.get(org.apache.jena.probsparql.ProbSPARQL.SIMILARITYJOIN_TOLERANCE);
        
        // Convert String variable names to Var objects
        Var simLeftVar = simLeftVarStr != null ? Var.alloc(simLeftVarStr) : null;
        Var simRightVar = simRightVarStr != null ? Var.alloc(simRightVarStr) : null;
        
        // Apply standard optimizations FIRST
        Op optimized = Algebra.optimize(op, super.context);
        
        // New relational semantics: FUSEJOIN with left and right patterns
        // Check for non-empty patterns (legacy mode uses empty patterns)
        if (fuseLeftPattern != null && fuseRightPattern != null && 
            !fuseLeftPattern.trim().isEmpty() && !fuseRightPattern.trim().isEmpty() &&
            fuseLeftVar != null && fuseRightVar != null && 
            fuseTolerance != null && fuseResultVar != null) {
            
            // Compile left and right patterns separately
            Op leftOp = compilePattern(fuseLeftPattern);
            Op rightOp = compilePattern(fuseRightPattern);
            
            // Create OpFuseJoin with both inputs (NOT legacy mode)
            OpFuseJoin fuseJoin = new OpFuseJoin(leftOp, rightOp, fuseLeftVar, fuseRightVar, fuseTolerance, fuseResultVar, false);
            
            // Insert into query algebra tree
            Op transformed = insertFuseJoinRelational(optimized, fuseJoin);
            return transformed;
        }
        
        // New relational semantics: SIMILARITYJOIN with left and right patterns
        // Check for non-empty patterns (legacy mode uses empty patterns)
        if (simLeftPattern != null && simRightPattern != null && 
            !simLeftPattern.trim().isEmpty() && !simRightPattern.trim().isEmpty() &&
            simLeftVar != null && simRightVar != null && simTolerance != null) {
            
            // Compile left and right patterns separately
            Op leftOp = compilePattern(simLeftPattern);
            Op rightOp = compilePattern(simRightPattern);
            
            // Create OpSimilarityJoin with both inputs (NOT legacy mode)
            OpSimilarityJoin simJoin = new OpSimilarityJoin(leftOp, rightOp, simLeftVar, simRightVar, simTolerance, false);
            
            // Insert into query algebra tree
            Op transformed = insertSimilarityJoinRelational(optimized, simJoin);
            return transformed;
        }
        
        // Fallback: Check for legacy single-pattern FUSEJOIN
        if (fuseLeftVar != null && fuseRightVar != null && fuseTolerance != null && fuseResultVar != null) {
            // Legacy mode: insert FuseJoin wrapping existing BGP
            Op transformed = insertFuseJoinLegacy(optimized, fuseLeftVar.getName(), fuseRightVar.getName(), fuseTolerance, fuseResultVar.getName());
            return transformed;
        }
        
        // Fallback: Check for legacy single-pattern SIMILARITYJOIN
        if (simLeftVar != null && simRightVar != null && simTolerance != null) {
            // Legacy mode: insert SimilarityJoin wrapping existing BGP
            Op transformed = insertSimilarityJoinLegacy(optimized, simLeftVar.getName(), simRightVar.getName(), simTolerance);
            return transformed;
        }
        
        // No special operators, use standard optimization
        return super.modifyOp(op);
    }
    
    /**
     * Compile a SPARQL pattern string into an Op.
     * Creates a minimal SELECT query from the pattern and compiles to algebra.
     * Uses the original query's prefix mappings to resolve prefixed names.
     */
    private Op compilePattern(String pattern) {
        if (pattern == null || pattern.trim().isEmpty()) {
            return org.apache.jena.sparql.algebra.op.OpTable.unit();
        }
        
        // Build a query with prefix declarations from the original query
        StringBuilder queryStr = new StringBuilder();
        
        // Add prefix declarations from the original query
        if (originalQuery != null && originalQuery.getPrefixMapping() != null) {
            java.util.Map<String, String> prefixMap = originalQuery.getPrefixMapping().getNsPrefixMap();
            for (java.util.Map.Entry<String, String> entry : prefixMap.entrySet()) {
                queryStr.append("PREFIX ").append(entry.getKey()).append(": <").append(entry.getValue()).append(">\n");
            }
        }
        
        queryStr.append("SELECT * WHERE { ");
        queryStr.append(pattern);
        queryStr.append(" }");
        
        try {
            Query subQuery = QueryFactory.create(queryStr.toString());
            return Algebra.compile(subQuery);
        } catch (Exception e) {
            System.err.println("Warning: Failed to compile pattern: " + pattern);
            e.printStackTrace();
            return org.apache.jena.sparql.algebra.op.OpTable.unit();
        }
    }
    
    /**
     * Insert OpFuseJoin with relational semantics (dual-input).
     * The OpFuseJoin already contains compiled left and right ops.
     */
    private Op insertFuseJoinRelational(Op originalOp, OpFuseJoin fuseJoin) {
        InsertFuseJoinRelationalTransform transform = new InsertFuseJoinRelationalTransform(fuseJoin);
        return Transformer.transform(transform, originalOp);
    }
    
    /**
     * Insert OpSimilarityJoin with relational semantics (dual-input).
     * The OpSimilarityJoin already contains compiled left and right ops.
     */
    private Op insertSimilarityJoinRelational(Op originalOp, OpSimilarityJoin simJoin) {
        InsertSimilarityJoinRelationalTransform transform = new InsertSimilarityJoinRelationalTransform(simJoin);
        return Transformer.transform(transform, originalOp);
    }
    
    /**
     * Legacy: Insert OpFuseJoin into the algebra tree (single-input mode).
     */
    private Op insertFuseJoinLegacy(Op op, String leftVar, String rightVar, double tolerance, String resultVar) {
        InsertFuseJoinLegacyTransform transform = new InsertFuseJoinLegacyTransform(leftVar, rightVar, tolerance, resultVar);
        return Transformer.transform(transform, op);
    }
    
    /**
     * Legacy: Insert OpSimilarityJoin into the algebra tree (single-input mode).
     */
    private Op insertSimilarityJoinLegacy(Op op, String leftVar, String rightVar, double tolerance) {
        InsertSimilarityJoinLegacyTransform transform = new InsertSimilarityJoinLegacyTransform(leftVar, rightVar, tolerance);
        return Transformer.transform(transform, op);
    }
    
    /**
     * Transformer for relational semantics FUSEJOIN.
     * Replaces the query body with the pre-constructed OpFuseJoin.
     */
    private static class InsertFuseJoinRelationalTransform extends TransformCopy {
        private final OpFuseJoin fuseJoin;
        private boolean inserted = false;
        
        public InsertFuseJoinRelationalTransform(OpFuseJoin fuseJoin) {
            this.fuseJoin = fuseJoin;
        }
        
        @Override
        public Op transform(org.apache.jena.sparql.algebra.op.OpProject opProject, Op subOp) {
            if (!inserted) {
                inserted = true;
                
                // Handle OpExtend (BIND operations)
                if (subOp instanceof org.apache.jena.sparql.algebra.op.OpExtend) {
                    org.apache.jena.sparql.algebra.op.OpExtend extend = 
                        (org.apache.jena.sparql.algebra.op.OpExtend) subOp;
                    
                    // Rebuild OpExtend with OpFuseJoin as its subOp
                    Op newExtend = org.apache.jena.sparql.algebra.op.OpExtend.create(
                        fuseJoin, 
                        extend.getVarExprList()
                    );
                    
                    return new org.apache.jena.sparql.algebra.op.OpProject(newExtend, opProject.getVars());
                } else {
                    // Replace directly
                    return new org.apache.jena.sparql.algebra.op.OpProject(fuseJoin, opProject.getVars());
                }
            }
            return super.transform(opProject, subOp);
        }
    }
    
    /**
     * Transformer for relational semantics SIMILARITYJOIN.
     * Replaces the query body with the pre-constructed OpSimilarityJoin.
     */
    private static class InsertSimilarityJoinRelationalTransform extends TransformCopy {
        private final OpSimilarityJoin simJoin;
        private boolean inserted = false;
        
        public InsertSimilarityJoinRelationalTransform(OpSimilarityJoin simJoin) {
            this.simJoin = simJoin;
        }
        
        @Override
        public Op transform(org.apache.jena.sparql.algebra.op.OpProject opProject, Op subOp) {
            if (!inserted) {
                inserted = true;
                
                // Handle OpExtend (BIND operations)
                if (subOp instanceof org.apache.jena.sparql.algebra.op.OpExtend) {
                    org.apache.jena.sparql.algebra.op.OpExtend extend = 
                        (org.apache.jena.sparql.algebra.op.OpExtend) subOp;
                    
                    // Rebuild OpExtend with OpSimilarityJoin as its subOp
                    Op newExtend = org.apache.jena.sparql.algebra.op.OpExtend.create(
                        simJoin, 
                        extend.getVarExprList()
                    );
                    
                    return new org.apache.jena.sparql.algebra.op.OpProject(newExtend, opProject.getVars());
                } else {
                    // Replace directly
                    return new org.apache.jena.sparql.algebra.op.OpProject(simJoin, opProject.getVars());
                }
            }
            return super.transform(opProject, subOp);
        }
    }
    
    /**
     * Legacy transformer for single-input FUSEJOIN.
     */
    private static class InsertFuseJoinLegacyTransform extends TransformCopy {
        private final String leftVar;
        private final String rightVar;
        private final double tolerance;
        private final String resultVar;
        private boolean inserted = false;
        
        public InsertFuseJoinLegacyTransform(String leftVar, String rightVar, double tolerance, String resultVar) {
            this.leftVar = leftVar;
            this.rightVar = rightVar;
            this.tolerance = tolerance;
            this.resultVar = resultVar;
        }
        
        @Override
        public Op transform(org.apache.jena.sparql.algebra.op.OpProject opProject, Op subOp) {
            if (!inserted) {
                inserted = true;
                
                if (subOp instanceof org.apache.jena.sparql.algebra.op.OpExtend) {
                    org.apache.jena.sparql.algebra.op.OpExtend extend = 
                        (org.apache.jena.sparql.algebra.op.OpExtend) subOp;
                    
                    // Legacy: Create single-input OpFuseJoin with legacyMode=true
                    OpFuseJoin fusejoin = new OpFuseJoin(
                        extend.getSubOp(),
                        extend.getSubOp(),
                        Var.alloc(leftVar),
                        Var.alloc(rightVar),
                        tolerance,
                        Var.alloc(resultVar),
                        true  // legacyMode
                    );
                    
                    Op newExtend = org.apache.jena.sparql.algebra.op.OpExtend.create(
                        fusejoin, 
                        extend.getVarExprList()
                    );
                    
                    return new org.apache.jena.sparql.algebra.op.OpProject(newExtend, opProject.getVars());
                } else {
                    // Legacy: Create single-input OpFuseJoin with legacyMode=true
                    OpFuseJoin fusejoin = new OpFuseJoin(
                        subOp,
                        subOp,
                        Var.alloc(leftVar),
                        Var.alloc(rightVar),
                        tolerance,
                        Var.alloc(resultVar),
                        true  // legacyMode
                    );
                    
                    return new org.apache.jena.sparql.algebra.op.OpProject(fusejoin, opProject.getVars());
                }
            }
            return super.transform(opProject, subOp);
        }
    }
    
    /**
     * Legacy transformer for single-input SIMILARITYJOIN.
     */
    private static class InsertSimilarityJoinLegacyTransform extends TransformCopy {
        private final String leftVar;
        private final String rightVar;
        private final double tolerance;
        private boolean inserted = false;
        
        public InsertSimilarityJoinLegacyTransform(String leftVar, String rightVar, double tolerance) {
            this.leftVar = leftVar;
            this.rightVar = rightVar;
            this.tolerance = tolerance;
        }
        
        @Override
        public Op transform(org.apache.jena.sparql.algebra.op.OpProject opProject, Op subOp) {
            if (!inserted) {
                inserted = true;
                
                if (subOp instanceof org.apache.jena.sparql.algebra.op.OpExtend) {
                    org.apache.jena.sparql.algebra.op.OpExtend extend = 
                        (org.apache.jena.sparql.algebra.op.OpExtend) subOp;
                    
                    // Legacy: Create single-input OpSimilarityJoin with legacyMode=true
                    OpSimilarityJoin similarityjoin = new OpSimilarityJoin(
                        extend.getSubOp(),
                        extend.getSubOp(),
                        Var.alloc(leftVar),
                        Var.alloc(rightVar),
                        tolerance,
                        true  // legacyMode
                    );
                    
                    Op newExtend = org.apache.jena.sparql.algebra.op.OpExtend.create(
                        similarityjoin, 
                        extend.getVarExprList()
                    );
                    
                    return new org.apache.jena.sparql.algebra.op.OpProject(newExtend, opProject.getVars());
                } else {
                    // Legacy: Create single-input OpSimilarityJoin with legacyMode=true
                    OpSimilarityJoin similarityjoin = new OpSimilarityJoin(
                        subOp,
                        subOp,
                        Var.alloc(leftVar),
                        Var.alloc(rightVar),
                        tolerance,
                        true  // legacyMode
                    );
                    
                    return new org.apache.jena.sparql.algebra.op.OpProject(similarityjoin, opProject.getVars());
                }
            }
            return super.transform(opProject, subOp);
        }
    }
    
    /**
     * Factory for creating QueryEngineProbabilistic instances.
     * This factory has higher priority than the default QueryEngineMain factory.
     */
    public static class Factory implements QueryEngineFactory {
        
        @Override
        public boolean accept(Query query, DatasetGraph dataset, Context context) {
            // Accept all queries - we handle both standard SPARQL and ProbSPARQL extensions
            return true;
        }
        
        @Override
        public Plan create(Query query, DatasetGraph dataset, Binding input, Context context) {
            QueryEngineProbabilistic engine = new QueryEngineProbabilistic(query, dataset, input, context);
            return engine.getPlan();
        }
        
        @Override
        public boolean accept(Op op, DatasetGraph dataset, Context context) {
            // Accept algebra-level queries as well
            return true;
        }
        
        @Override
        public Plan create(Op op, DatasetGraph dataset, Binding input, Context context) {
            // For algebra-level execution, delegate to standard engine
            return QueryEngineMain.getFactory().create(op, dataset, input, context);
        }
    }
}
