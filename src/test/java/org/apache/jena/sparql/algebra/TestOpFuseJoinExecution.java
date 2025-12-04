package org.apache.jena.sparql.algebra;

import org.apache.jena.atlas.lib.StrUtils;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.algebra.op.OpFuseJoin;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Var;

/**
 * Test OpFuseJoin execution through OpExecutorProbabilistic.
 */
public class TestOpFuseJoinExecution {
    
    public static void main(String[] args) {
        // Create test data
        Model model = ModelFactory.createDefaultModel();
        String ttl = StrUtils.strjoinNL(
            "@prefix ex: <http://example.org/> .",
            "@prefix uq: <http://example.org/ontology/uncertainty#> .",
            "",
            "ex:measurement1 uq:hasDistribution ex:dist1 .",
            "ex:measurement2 uq:hasDistribution ex:dist2 ."
        );
        model.read(new java.io.StringReader(ttl), null, "TTL");
        
        // Create BGPs for left and right tables
        BasicPattern leftPattern = new BasicPattern();
        leftPattern.add(org.apache.jena.graph.Triple.create(
            Var.alloc("m1"),
            org.apache.jena.graph.NodeFactory.createURI("http://example.org/ontology/uncertainty#hasDistribution"),
            Var.alloc("caliperDist")
        ));
        OpBGP leftBgp = new OpBGP(leftPattern);
        
        BasicPattern rightPattern = new BasicPattern();
        rightPattern.add(org.apache.jena.graph.Triple.create(
            Var.alloc("m2"),
            org.apache.jena.graph.NodeFactory.createURI("http://example.org/ontology/uncertainty#hasDistribution"),
            Var.alloc("laserDist")
        ));
        OpBGP rightBgp = new OpBGP(rightPattern);
        
        // Create Var objects for the join parameters
        Var leftVar = Var.alloc("caliperDist");
        Var rightVar = Var.alloc("laserDist");
        Var resultVar = Var.alloc("fusedDist");
        
        // Create OpFuseJoin with new relational semantics (left and right ops)
        OpFuseJoin fusejoin = new OpFuseJoin(leftBgp, rightBgp, leftVar, rightVar, 0.1, resultVar);
        
        System.out.println("OpFuseJoin created:");
        System.out.println(fusejoin);
        System.out.println("  getName: " + fusejoin.getName());
        System.out.println("  getLeftVar: " + fusejoin.getLeftVar());
        System.out.println("  getRightVar: " + fusejoin.getRightVar());
        System.out.println("  getTolerance: " + fusejoin.getTolerance());
        System.out.println("  getResultVar: " + fusejoin.getResultVar());
        System.out.println("  isLegacyMode: " + fusejoin.isLegacyMode());
        
        // Use Query-based execution (simpler and more robust)
        // Create a query that will be executed with FUSEJOIN metadata
        String queryString = "SELECT * WHERE { ?m <http://example.org/ontology/uncertainty#hasDistribution> ?dist }";
        Query query = QueryFactory.create(queryString);
        
        System.out.println("\nExecuting through QueryExecution...");
        
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            // Set FUSEJOIN metadata in context (this is how QueryRunner does it)
            qexec.getContext().set(org.apache.jena.probsparql.ProbSPARQL.FUSEJOIN_LEFT_VAR, "caliperDist");
            qexec.getContext().set(org.apache.jena.probsparql.ProbSPARQL.FUSEJOIN_RIGHT_VAR, "laserDist");
            qexec.getContext().set(org.apache.jena.probsparql.ProbSPARQL.FUSEJOIN_TOLERANCE, 0.1);
            qexec.getContext().set(org.apache.jena.probsparql.ProbSPARQL.FUSEJOIN_RESULT_VAR, "fusedDist");
            
            ResultSet results = qexec.execSelect();
            
            System.out.println("\nExecution started successfully!");
            
            // Iterate results
            int count = 0;
            while (results.hasNext()) {
                QuerySolution sol = results.next();
                System.out.println("  Row " + (++count) + ": " + sol);
            }
            
            System.out.println("\nTotal rows: " + count);
            
        } catch (Exception e) {
            System.err.println("ERROR during execution:");
            e.printStackTrace();
        }
    }
}
