package org.apache.jena.probsparql.propertyfunctions;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for probabilistic join property functions.
 */
public class ProbabilisticJoinTest {
    
    private static final String UQ_NS = "http://example.org/ontology/uncertainty#";
    private static final String PROB_NS = "http://probsparql.org/property#";
    private static final String EX_NS = "http://example.org/";
    
    @BeforeAll
    public static void setup() {
        ProbSPARQL.init();
    }
    
    @Test
    public void testExactJoin_MatchesIdenticalDistributions() {
        // Create model with two sensors having identical GMMs
        Model model = ModelFactory.createDefaultModel();
        
        // GMM: N(5.0, 1.0)
        GMMValue gmm = new GMMValue(
            1, 1, "full",
            new double[]{1.0},
            new double[][]{{5.0}},
            new double[][][]{{{1.0}}}
        );
        
        Node gmmNode = NodeFactory.createLiteralDT(gmm.toJSON(), GMMDatatype.INSTANCE);
        
        Resource sensor1 = model.createResource(EX_NS + "sensor1");
        Resource sensor2 = model.createResource(EX_NS + "sensor2");
        
        sensor1.addProperty(
            model.createProperty(UQ_NS + "hasDistribution"),
            model.asRDFNode(gmmNode)
        );
        sensor2.addProperty(
            model.createProperty(UQ_NS + "hasDistribution"),
            model.asRDFNode(gmmNode)
        );
        
        // Query with exact join
        String queryStr = 
            "PREFIX uq: <" + UQ_NS + ">\n" +
            "PREFIX prob: <" + PROB_NS + ">\n" +
            "SELECT ?s1 ?s2 ?fused WHERE {\n" +
            "  ?s1 uq:hasDistribution ?gmm1 .\n" +
            "  ?s2 uq:hasDistribution ?gmm2 .\n" +
            "  (?s1 ?s2) prob:exactJoin (?gmm1 ?gmm2 ?fused) .\n" +
            "}";
        
        try (QueryExecution qexec = QueryExecutionFactory.create(queryStr, model)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext(), "Should find matching distributions");
            
            QuerySolution soln = results.next();
            assertNotNull(soln.get("fused"), "Fused GMM should be bound");
            
            // Verify fused result is a GMM
            Node fusedNode = soln.get("fused").asNode();
            assertTrue(fusedNode.isLiteral());
            assertTrue(fusedNode.getLiteralValue() instanceof GMMValue);
        }
    }
    
    @Test
    public void testExactJoin_RejectsNonIdenticalDistributions() {
        Model model = ModelFactory.createDefaultModel();
        
        // GMM1: N(5.0, 1.0)
        GMMValue gmm1 = new GMMValue(
            1, 1, "full",
            new double[]{1.0},
            new double[][]{{5.0}},
            new double[][][]{{{1.0}}}
        );
        
        // GMM2: N(6.0, 1.0) - different mean
        GMMValue gmm2 = new GMMValue(
            1, 1, "full",
            new double[]{1.0},
            new double[][]{{6.0}},
            new double[][][]{{{1.0}}}
        );
        
        Resource sensor1 = model.createResource(EX_NS + "sensor1");
        Resource sensor2 = model.createResource(EX_NS + "sensor2");
        
        sensor1.addProperty(
            model.createProperty(UQ_NS + "hasDistribution"),
            model.asRDFNode(NodeFactory.createLiteralDT(gmm1.toJSON(), GMMDatatype.INSTANCE))
        );
        sensor2.addProperty(
            model.createProperty(UQ_NS + "hasDistribution"),
            model.asRDFNode(NodeFactory.createLiteralDT(gmm2.toJSON(), GMMDatatype.INSTANCE))
        );
        
        String queryStr = 
            "PREFIX uq: <" + UQ_NS + ">\n" +
            "PREFIX prob: <" + PROB_NS + ">\n" +
            "SELECT ?s1 ?s2 ?fused WHERE {\n" +
            "  ?s1 uq:hasDistribution ?gmm1 .\n" +
            "  ?s2 uq:hasDistribution ?gmm2 .\n" +
            "  (?s1 ?s2) prob:exactJoin (?gmm1 ?gmm2 ?fused) .\n" +
            "}";
        
        try (QueryExecution qexec = QueryExecutionFactory.create(queryStr, model)) {
            ResultSet results = qexec.execSelect();
            assertFalse(results.hasNext(), "Should not match different distributions");
        }
    }
    
    @Test
    public void testFuzzyJoin_MatchesSimilarDistributions() {
        Model model = ModelFactory.createDefaultModel();
        
        // GMM1: N(5.0, 1.0)
        GMMValue gmm1 = new GMMValue(
            1, 1, "full",
            new double[]{1.0},
            new double[][]{{5.0}},
            new double[][][]{{{1.0}}}
        );
        
        // GMM2: N(5.2, 1.0) - slightly different mean
        GMMValue gmm2 = new GMMValue(
            1, 1, "full",
            new double[]{1.0},
            new double[][]{{5.2}},
            new double[][][]{{{1.0}}}
        );
        
        Resource sensor1 = model.createResource(EX_NS + "sensor1");
        Resource sensor2 = model.createResource(EX_NS + "sensor2");
        
        sensor1.addProperty(
            model.createProperty(UQ_NS + "hasDistribution"),
            model.asRDFNode(NodeFactory.createLiteralDT(gmm1.toJSON(), GMMDatatype.INSTANCE))
        );
        sensor2.addProperty(
            model.createProperty(UQ_NS + "hasDistribution"),
            model.asRDFNode(NodeFactory.createLiteralDT(gmm2.toJSON(), GMMDatatype.INSTANCE))
        );
        
        // Query with fuzzy join (tolerance = 0.1)
        String queryStr = 
            "PREFIX uq: <" + UQ_NS + ">\n" +
            "PREFIX prob: <" + PROB_NS + ">\n" +
            "SELECT ?s1 ?s2 ?fused WHERE {\n" +
            "  ?s1 uq:hasDistribution ?gmm1 .\n" +
            "  ?s2 uq:hasDistribution ?gmm2 .\n" +
            "  (?s1 ?s2) prob:fuzzyJoin (?gmm1 ?gmm2 0.1 ?fused) .\n" +
            "}";
        
        try (QueryExecution qexec = QueryExecutionFactory.create(queryStr, model)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext(), "Should match similar distributions with tolerance 0.1");
            
            QuerySolution soln = results.next();
            assertNotNull(soln.get("fused"), "Fused GMM should be bound");
            
            Node fusedNode = soln.get("fused").asNode();
            assertTrue(fusedNode.isLiteral());
            GMMValue fusedGMM = (GMMValue) fusedNode.getLiteralValue();
            
            // Fused GMM should have K=1 (product of two single Gaussians)
            assertEquals(1, fusedGMM.getK());
        }
    }
    
    @Test
    public void testFuzzyJoin_RejectsVerydifferentDistributions() {
        Model model = ModelFactory.createDefaultModel();
        
        // GMM1: N(0.0, 1.0)
        GMMValue gmm1 = new GMMValue(
            1, 1, "full",
            new double[]{1.0},
            new double[][]{{0.0}},
            new double[][][]{{{1.0}}}
        );
        
        // GMM2: N(10.0, 1.0) - very different mean
        GMMValue gmm2 = new GMMValue(
            1, 1, "full",
            new double[]{1.0},
            new double[][]{{10.0}},
            new double[][][]{{{1.0}}}
        );
        
        Resource sensor1 = model.createResource(EX_NS + "sensor1");
        Resource sensor2 = model.createResource(EX_NS + "sensor2");
        
        sensor1.addProperty(
            model.createProperty(UQ_NS + "hasDistribution"),
            model.asRDFNode(NodeFactory.createLiteralDT(gmm1.toJSON(), GMMDatatype.INSTANCE))
        );
        sensor2.addProperty(
            model.createProperty(UQ_NS + "hasDistribution"),
            model.asRDFNode(NodeFactory.createLiteralDT(gmm2.toJSON(), GMMDatatype.INSTANCE))
        );
        
        // Query with strict tolerance (0.01)
        String queryStr = 
            "PREFIX uq: <" + UQ_NS + ">\n" +
            "PREFIX prob: <" + PROB_NS + ">\n" +
            "SELECT ?s1 ?s2 ?fused WHERE {\n" +
            "  ?s1 uq:hasDistribution ?gmm1 .\n" +
            "  ?s2 uq:hasDistribution ?gmm2 .\n" +
            "  (?s1 ?s2) prob:fuzzyJoin (?gmm1 ?gmm2 0.01 ?fused) .\n" +
            "}";
        
        try (QueryExecution qexec = QueryExecutionFactory.create(queryStr, model)) {
            ResultSet results = qexec.execSelect();
            assertFalse(results.hasNext(), "Should not match very different distributions");
        }
    }
    
    @Test
    public void testFuzzyJoin_MultipleSensors() {
        Model model = ModelFactory.createDefaultModel();
        
        // Create 3 sensors with similar measurements
        // Sensor1: N(5.0, 1.0)
        GMMValue gmm1 = new GMMValue(
            1, 1, "full",
            new double[]{1.0},
            new double[][]{{5.0}},
            new double[][][]{{{1.0}}}
        );
        
        // Sensor2: N(5.1, 1.0)
        GMMValue gmm2 = new GMMValue(
            1, 1, "full",
            new double[]{1.0},
            new double[][]{{5.1}},
            new double[][][]{{{1.0}}}
        );
        
        // Sensor3: N(5.05, 1.0)
        GMMValue gmm3 = new GMMValue(
            1, 1, "full",
            new double[]{1.0},
            new double[][]{{5.05}},
            new double[][][]{{{1.0}}}
        );
        
        Resource sensor1 = model.createResource(EX_NS + "sensor1");
        Resource sensor2 = model.createResource(EX_NS + "sensor2");
        Resource sensor3 = model.createResource(EX_NS + "sensor3");
        
        sensor1.addProperty(
            model.createProperty(UQ_NS + "hasDistribution"),
            model.asRDFNode(NodeFactory.createLiteralDT(gmm1.toJSON(), GMMDatatype.INSTANCE))
        );
        sensor2.addProperty(
            model.createProperty(UQ_NS + "hasDistribution"),
            model.asRDFNode(NodeFactory.createLiteralDT(gmm2.toJSON(), GMMDatatype.INSTANCE))
        );
        sensor3.addProperty(
            model.createProperty(UQ_NS + "hasDistribution"),
            model.asRDFNode(NodeFactory.createLiteralDT(gmm3.toJSON(), GMMDatatype.INSTANCE))
        );
        
        // Query finds all pairs with JS < 0.05
        String queryStr = 
            "PREFIX uq: <" + UQ_NS + ">\n" +
            "PREFIX prob: <" + PROB_NS + ">\n" +
            "SELECT ?s1 ?s2 WHERE {\n" +
            "  ?s1 uq:hasDistribution ?gmm1 .\n" +
            "  ?s2 uq:hasDistribution ?gmm2 .\n" +
            "  FILTER(?s1 != ?s2)\n" +
            "  (?s1 ?s2) prob:fuzzyJoin (?gmm1 ?gmm2 0.05 ?fused) .\n" +
            "}";
        
        try (QueryExecution qexec = QueryExecutionFactory.create(queryStr, model)) {
            ResultSet results = qexec.execSelect();
            
            int count = 0;
            while (results.hasNext()) {
                results.next();
                count++;
            }
            
            // Should find multiple matching pairs
            assertTrue(count > 0, "Should find at least one matching pair");
        }
    }
    
    @Test
    public void testFuzzyJoin_BimodalDistributions() {
        Model model = ModelFactory.createDefaultModel();
        
        // GMM1: Bimodal 0.5*N(0, 1) + 0.5*N(5, 1)
        GMMValue gmm1 = new GMMValue(
            2, 1, "full",
            new double[]{0.5, 0.5},
            new double[][]{{0.0}, {5.0}},
            new double[][][]{{{1.0}}, {{1.0}}}
        );
        
        // GMM2: Bimodal 0.5*N(0.2, 1) + 0.5*N(5.2, 1) - similar but shifted
        GMMValue gmm2 = new GMMValue(
            2, 1, "full",
            new double[]{0.5, 0.5},
            new double[][]{{0.2}, {5.2}},
            new double[][][]{{{1.0}}, {{1.0}}}
        );
        
        Resource dist1 = model.createResource(EX_NS + "dist1");
        Resource dist2 = model.createResource(EX_NS + "dist2");
        
        dist1.addProperty(
            model.createProperty(UQ_NS + "hasDistribution"),
            model.asRDFNode(NodeFactory.createLiteralDT(gmm1.toJSON(), GMMDatatype.INSTANCE))
        );
        dist2.addProperty(
            model.createProperty(UQ_NS + "hasDistribution"),
            model.asRDFNode(NodeFactory.createLiteralDT(gmm2.toJSON(), GMMDatatype.INSTANCE))
        );
        
        String queryStr = 
            "PREFIX uq: <" + UQ_NS + ">\n" +
            "PREFIX prob: <" + PROB_NS + ">\n" +
            "SELECT ?fused WHERE {\n" +
            "  ?d1 uq:hasDistribution ?gmm1 .\n" +
            "  ?d2 uq:hasDistribution ?gmm2 .\n" +
            "  (?d1 ?d2) prob:fuzzyJoin (?gmm1 ?gmm2 0.2 ?fused) .\n" +
            "}";
        
        try (QueryExecution qexec = QueryExecutionFactory.create(queryStr, model)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext(), "Should match similar bimodal distributions");
            
            QuerySolution soln = results.next();
            Node fusedNode = soln.get("fused").asNode();
            GMMValue fusedGMM = (GMMValue) fusedNode.getLiteralValue();
            
            // Fusion of two bimodal GMMs: K = 2 * 2 = 4
            assertEquals(4, fusedGMM.getK());
        }
    }
    
    @Test
    public void testFuzzyJoin_ToleranceValidation() {
        Model model = ModelFactory.createDefaultModel();
        
        GMMValue gmm = new GMMValue(
            1, 1, "full",
            new double[]{1.0},
            new double[][]{{5.0}},
            new double[][][]{{{1.0}}}
        );
        
        Resource sensor = model.createResource(EX_NS + "sensor");
        sensor.addProperty(
            model.createProperty(UQ_NS + "hasDistribution"),
            model.asRDFNode(NodeFactory.createLiteralDT(gmm.toJSON(), GMMDatatype.INSTANCE))
        );
        
        // Test with invalid tolerance (> 1)
        String queryStr = 
            "PREFIX uq: <" + UQ_NS + ">\n" +
            "PREFIX prob: <" + PROB_NS + ">\n" +
            "SELECT ?fused WHERE {\n" +
            "  ?s uq:hasDistribution ?gmm .\n" +
            "  (?s ?s) prob:fuzzyJoin (?gmm ?gmm 1.5 ?fused) .\n" +
            "}";
        
        assertThrows(Exception.class, () -> {
            try (QueryExecution qexec = QueryExecutionFactory.create(queryStr, model)) {
                qexec.execSelect().hasNext();
            }
        }, "Should reject invalid tolerance > 1");
    }
}
