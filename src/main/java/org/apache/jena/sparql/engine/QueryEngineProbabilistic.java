package org.apache.jena.sparql.engine;

import org.apache.jena.query.Query;
import org.apache.jena.sparql.ARQConstants;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.main.OpExecutorProbabilistic;
import org.apache.jena.sparql.engine.main.QueryEngineMain;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.sparql.syntax.*;

/**
 * Custom query engine for ProbSPARQL that handles FUSEJOIN and SIMILARITYJOIN operations.
 * 
 * This engine integrates seamlessly with Jena's query execution pipeline:
 * 1. Uses AlgebraGeneratorProbabilistic to compile ElementSimilarityJoin/ElementFuseJoin
 *    into OpSimilarityJoin/OpFuseJoin operators
 * 2. Registers OpExecutorProbabilistic to execute these custom operators
 * 
 * Supports both relational and legacy semantics:
 * - Relational: { leftPattern } SIMILARITYJOIN(?v1, ?v2, tol) { rightPattern }
 * - Legacy: SIMILARITYJOIN(?v1, ?v2, tol) { pattern }
 */
public class QueryEngineProbabilistic extends QueryEngineMain {
    
    public QueryEngineProbabilistic(Query query, DatasetGraph dataset, Binding input, Context context) {
        super(query, dataset, input, context);
        // Register our custom OpExecutor factory
        context.set(ARQConstants.sysOpExecutorFactory, OpExecutorProbabilistic.factory);
    }
    
    @Override
    protected Op createOp(Query query) {
        // Use AlgebraGeneratorProbabilistic with context to ensure proper compilation
        // This ensures ElementSimilarityJoin and ElementFuseJoin are properly compiled
        // into OpSimilarityJoin and OpFuseJoin operators
        Op op = new org.apache.jena.sparql.algebra.AlgebraGeneratorProbabilistic(super.context).compile(query);
        return op;
    }
    
    /**
     * Factory for creating QueryEngineProbabilistic instances.
     * This factory has higher priority than the default QueryEngineMain factory.
     */
    public static class Factory implements QueryEngineFactory {
        
        @Override
        public boolean accept(Query query, DatasetGraph dataset, Context context) {
            // Only accept queries that contain ProbSPARQL extensions
            if (query != null && query.getQueryPattern() != null) {
                return containsProbSPARQLElements(query.getQueryPattern());
            }
            return false;
        }
        
        private boolean containsProbSPARQLElements(Element element) {
            if (element == null) return false;
            
            // Check if this element is a ProbSPARQL element
            if (element instanceof ElementSimilarityJoin || element instanceof ElementFuseJoin) {
                return true;
            }
            
            // Recursively check child elements
            if (element instanceof ElementGroup) {
                ElementGroup group = (ElementGroup) element;
                for (Element child : group.getElements()) {
                    if (containsProbSPARQLElements(child)) {
                        return true;
                    }
                }
            } else if (element instanceof ElementOptional) {
                return containsProbSPARQLElements(((ElementOptional) element).getOptionalElement());
            } else if (element instanceof ElementUnion) {
                ElementUnion union = (ElementUnion) element;
                for (Element child : union.getElements()) {
                    if (containsProbSPARQLElements(child)) {
                        return true;
                    }
                }
            } else if (element instanceof ElementSubQuery) {
                Query subQuery = ((ElementSubQuery) element).getQuery();
                if (subQuery.getQueryPattern() != null) {
                    return containsProbSPARQLElements(subQuery.getQueryPattern());
                }
            }
            
            return false;
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
