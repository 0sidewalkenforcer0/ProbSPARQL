package org.apache.jena.probsparql.functions.comparison;

import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.probsparql.datatypes.DirichletDatatype;
import org.apache.jena.probsparql.datatypes.DirichletValue;
import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.expr.nodevalue.NodeFunctions;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for KL and JS divergence functions.
 * 
 * @author ProbSPARQL Team
 */
class ComparisonFunctionsTest {
    
    @BeforeAll
    static void setUp() {
        ProbSPARQL.init();
    }
    
    @Test
    void testKLDivergence_IdenticalDistributions() {
        Model model = ModelFactory.createDefaultModel();
        
        String uqNS = "http://example.org/ontology/uncertainty#";
        String exNS = "http://example.org/data/";
        
        Property hasDistribution = model.createProperty(uqNS + "hasDistribution");
        Resource sensor1 = model.createResource(exNS + "sensor1");
        Resource sensor2 = model.createResource(exNS + "sensor2");
        
        // Identical GMMs: N(5.0, 1.0)
        String gmmJson = "{\"n_components\":1,\"dimensions\":1,\"covariance_type\":\"full\"," +
                        "\"weights\":[1.0],\"means\":[[5.0]],\"covariances\":[[[1.0]]]}";
        
        Literal gmmLiteral = model.createTypedLiteral(gmmJson, GMMDatatype.INSTANCE);
        sensor1.addProperty(hasDistribution, gmmLiteral);
        sensor2.addProperty(hasDistribution, gmmLiteral);
        
        String queryString = 
            "PREFIX prob: <http://probsparql.org/function#> \n" +
            "PREFIX uq: <http://example.org/ontology/uncertainty#> \n" +
            "PREFIX ex: <http://example.org/data/> \n" +
            "SELECT ?kl WHERE { \n" +
            "  ex:sensor1 uq:hasDistribution ?gmm1 . \n" +
            "  ex:sensor2 uq:hasDistribution ?gmm2 . \n" +
            "  BIND(prob:kldivergence(?gmm1, ?gmm2) AS ?kl) \n" +
            "}";
        
        Query query = QueryFactory.create(queryString);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext());
            QuerySolution soln = results.next();
            double kl = soln.getLiteral("kl").getDouble();
            
            // KL divergence between identical distributions should be ~0
            assertEquals(0.0, kl, 0.05, "KL(P||P) should be approximately 0");
        }
    }
    
    @Test
    void testKLDivergence_Asymmetry() {
        Model model = ModelFactory.createDefaultModel();
        
        String uqNS = "http://example.org/ontology/uncertainty#";
        String exNS = "http://example.org/data/";
        
        Property hasDistribution = model.createProperty(uqNS + "hasDistribution");
        Resource sensor1 = model.createResource(exNS + "sensor1");
        Resource sensor2 = model.createResource(exNS + "sensor2");
        
        // GMM1: N(5.0, 1.0)
        String gmm1Json = "{\"n_components\":1,\"dimensions\":1,\"covariance_type\":\"full\"," +
                         "\"weights\":[1.0],\"means\":[[5.0]],\"covariances\":[[[1.0]]]}";
        
        // GMM2: N(6.0, 2.0)
        String gmm2Json = "{\"n_components\":1,\"dimensions\":1,\"covariance_type\":\"full\"," +
                         "\"weights\":[1.0],\"means\":[[6.0]],\"covariances\":[[[2.0]]]}";
        
        sensor1.addProperty(hasDistribution, 
                           model.createTypedLiteral(gmm1Json, GMMDatatype.INSTANCE));
        sensor2.addProperty(hasDistribution, 
                           model.createTypedLiteral(gmm2Json, GMMDatatype.INSTANCE));
        
        String queryString = 
            "PREFIX prob: <http://probsparql.org/function#> \n" +
            "PREFIX uq: <http://example.org/ontology/uncertainty#> \n" +
            "PREFIX ex: <http://example.org/data/> \n" +
            "SELECT ?kl1 ?kl2 WHERE { \n" +
            "  ex:sensor1 uq:hasDistribution ?gmm1 . \n" +
            "  ex:sensor2 uq:hasDistribution ?gmm2 . \n" +
            "  BIND(prob:kldivergence(?gmm1, ?gmm2) AS ?kl1) \n" +
            "  BIND(prob:kldivergence(?gmm2, ?gmm1) AS ?kl2) \n" +
            "}";
        
        Query query = QueryFactory.create(queryString);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext());
            QuerySolution soln = results.next();
            double kl1 = soln.getLiteral("kl1").getDouble();
            double kl2 = soln.getLiteral("kl2").getDouble();
            
            // KL divergence is asymmetric
            assertNotEquals(kl1, kl2, 0.1, "KL(P||Q) should differ from KL(Q||P)");
            
            // Both should be positive
            assertTrue(kl1 > 0, "KL divergence should be positive");
            assertTrue(kl2 > 0, "KL divergence should be positive");
        }
    }
    
    @Test
    void testJSDivergence_IdenticalDistributions() {
        Model model = ModelFactory.createDefaultModel();
        
        String uqNS = "http://example.org/ontology/uncertainty#";
        String exNS = "http://example.org/data/";
        
        Property hasDistribution = model.createProperty(uqNS + "hasDistribution");
        Resource sensor1 = model.createResource(exNS + "sensor1");
        Resource sensor2 = model.createResource(exNS + "sensor2");
        
        String gmmJson = "{\"n_components\":1,\"dimensions\":1,\"covariance_type\":\"full\"," +
                        "\"weights\":[1.0],\"means\":[[5.0]],\"covariances\":[[[1.0]]]}";
        
        Literal gmmLiteral = model.createTypedLiteral(gmmJson, GMMDatatype.INSTANCE);
        sensor1.addProperty(hasDistribution, gmmLiteral);
        sensor2.addProperty(hasDistribution, gmmLiteral);
        
        String queryString = 
            "PREFIX prob: <http://probsparql.org/function#> \n" +
            "PREFIX uq: <http://example.org/ontology/uncertainty#> \n" +
            "PREFIX ex: <http://example.org/data/> \n" +
            "SELECT ?js WHERE { \n" +
            "  ex:sensor1 uq:hasDistribution ?gmm1 . \n" +
            "  ex:sensor2 uq:hasDistribution ?gmm2 . \n" +
            "  BIND(prob:jsdivergence(?gmm1, ?gmm2) AS ?js) \n" +
            "}";
        
        Query query = QueryFactory.create(queryString);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext());
            QuerySolution soln = results.next();
            double js = soln.getLiteral("js").getDouble();
            
            // JS divergence between identical distributions should be ~0
            assertEquals(0.0, js, 0.05, "JS(P||P) should be approximately 0");
        }
    }
    
    @Test
    void testJSDivergence_Symmetry() {
        Model model = ModelFactory.createDefaultModel();
        
        String uqNS = "http://example.org/ontology/uncertainty#";
        String exNS = "http://example.org/data/";
        
        Property hasDistribution = model.createProperty(uqNS + "hasDistribution");
        Resource sensor1 = model.createResource(exNS + "sensor1");
        Resource sensor2 = model.createResource(exNS + "sensor2");
        
        String gmm1Json = "{\"n_components\":1,\"dimensions\":1,\"covariance_type\":\"full\"," +
                         "\"weights\":[1.0],\"means\":[[5.0]],\"covariances\":[[[1.0]]]}";
        
        String gmm2Json = "{\"n_components\":1,\"dimensions\":1,\"covariance_type\":\"full\"," +
                         "\"weights\":[1.0],\"means\":[[7.0]],\"covariances\":[[[1.5]]]}";
        
        sensor1.addProperty(hasDistribution, 
                           model.createTypedLiteral(gmm1Json, GMMDatatype.INSTANCE));
        sensor2.addProperty(hasDistribution, 
                           model.createTypedLiteral(gmm2Json, GMMDatatype.INSTANCE));
        
        String queryString = 
            "PREFIX prob: <http://probsparql.org/function#> \n" +
            "PREFIX uq: <http://example.org/ontology/uncertainty#> \n" +
            "PREFIX ex: <http://example.org/data/> \n" +
            "SELECT ?js1 ?js2 WHERE { \n" +
            "  ex:sensor1 uq:hasDistribution ?gmm1 . \n" +
            "  ex:sensor2 uq:hasDistribution ?gmm2 . \n" +
            "  BIND(prob:jsdivergence(?gmm1, ?gmm2) AS ?js1) \n" +
            "  BIND(prob:jsdivergence(?gmm2, ?gmm1) AS ?js2) \n" +
            "}";
        
        Query query = QueryFactory.create(queryString);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext());
            QuerySolution soln = results.next();
            double js1 = soln.getLiteral("js1").getDouble();
            double js2 = soln.getLiteral("js2").getDouble();
            
            // JS divergence is symmetric
            assertEquals(js1, js2, 0.05, "JS(P||Q) should equal JS(Q||P)");
            
            // Should be positive
            assertTrue(js1 > 0, "JS divergence should be positive");
            
            // Should be bounded by log(2)
            assertTrue(js1 <= Math.log(2) + 0.01, "JS divergence should be ≤ log(2)");
        }
    }
    
    @Test
    void testJSDivergence_Bounded() {
        Model model = ModelFactory.createDefaultModel();
        
        String uqNS = "http://example.org/ontology/uncertainty#";
        String exNS = "http://example.org/data/";
        
        Property hasDistribution = model.createProperty(uqNS + "hasDistribution");
        Resource sensor1 = model.createResource(exNS + "sensor1");
        Resource sensor2 = model.createResource(exNS + "sensor2");
        
        // Very different distributions
        String gmm1Json = "{\"n_components\":1,\"dimensions\":1,\"covariance_type\":\"full\"," +
                         "\"weights\":[1.0],\"means\":[[0.0]],\"covariances\":[[[0.1]]]}";
        
        String gmm2Json = "{\"n_components\":1,\"dimensions\":1,\"covariance_type\":\"full\"," +
                         "\"weights\":[1.0],\"means\":[[10.0]],\"covariances\":[[[0.1]]]}";
        
        sensor1.addProperty(hasDistribution, 
                           model.createTypedLiteral(gmm1Json, GMMDatatype.INSTANCE));
        sensor2.addProperty(hasDistribution, 
                           model.createTypedLiteral(gmm2Json, GMMDatatype.INSTANCE));
        
        String queryString = 
            "PREFIX prob: <http://probsparql.org/function#> \n" +
            "PREFIX uq: <http://example.org/ontology/uncertainty#> \n" +
            "PREFIX ex: <http://example.org/data/> \n" +
            "SELECT ?js WHERE { \n" +
            "  ex:sensor1 uq:hasDistribution ?gmm1 . \n" +
            "  ex:sensor2 uq:hasDistribution ?gmm2 . \n" +
            "  BIND(prob:jsdivergence(?gmm1, ?gmm2) AS ?js) \n" +
            "}";
        
        Query query = QueryFactory.create(queryString);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext());
            QuerySolution soln = results.next();
            double js = soln.getLiteral("js").getDouble();
            
            // JS should be bounded
            assertTrue(js >= 0, "JS divergence should be non-negative");
            assertTrue(js <= Math.log(2) + 0.05, 
                      "JS divergence should be ≤ log(2) ≈ 0.693");
        }
    }
    
    @Test
    void testKLDivergence_BimodalGMMs() {
        Model model = ModelFactory.createDefaultModel();
        
        String uqNS = "http://example.org/ontology/uncertainty#";
        String exNS = "http://example.org/data/";
        
        Property hasDistribution = model.createProperty(uqNS + "hasDistribution");
        Resource sensor1 = model.createResource(exNS + "sensor1");
        Resource sensor2 = model.createResource(exNS + "sensor2");
        
        // Bimodal GMM1
        String gmm1Json = "{\"n_components\":2,\"dimensions\":1,\"covariance_type\":\"full\"," +
                         "\"weights\":[0.5,0.5]," +
                         "\"means\":[[2.0],[8.0]]," +
                         "\"covariances\":[[[0.5]],[[0.5]]]}";
        
        // Bimodal GMM2 (different modes)
        String gmm2Json = "{\"n_components\":2,\"dimensions\":1,\"covariance_type\":\"full\"," +
                         "\"weights\":[0.6,0.4]," +
                         "\"means\":[[3.0],[9.0]]," +
                         "\"covariances\":[[[0.8]],[[0.8]]]}";
        
        sensor1.addProperty(hasDistribution, 
                           model.createTypedLiteral(gmm1Json, GMMDatatype.INSTANCE));
        sensor2.addProperty(hasDistribution, 
                           model.createTypedLiteral(gmm2Json, GMMDatatype.INSTANCE));
        
        String queryString = 
            "PREFIX prob: <http://probsparql.org/function#> \n" +
            "PREFIX uq: <http://example.org/ontology/uncertainty#> \n" +
            "PREFIX ex: <http://example.org/data/> \n" +
            "SELECT ?kl WHERE { \n" +
            "  ex:sensor1 uq:hasDistribution ?gmm1 . \n" +
            "  ex:sensor2 uq:hasDistribution ?gmm2 . \n" +
            "  BIND(prob:kldivergence(?gmm1, ?gmm2) AS ?kl) \n" +
            "}";
        
        Query query = QueryFactory.create(queryString);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext());
            QuerySolution soln = results.next();
            double kl = soln.getLiteral("kl").getDouble();
            
            // Should be finite and positive
            assertTrue(Double.isFinite(kl), "KL divergence should be finite");
            assertTrue(kl > 0, "KL divergence should be positive for different distributions");
        }
    }
    
    @Test
    void testComparisonFunctions_DifferentCovarianceTypes() {
        Model model = ModelFactory.createDefaultModel();
        
        String uqNS = "http://example.org/ontology/uncertainty#";
        String exNS = "http://example.org/data/";
        
        Property hasDistribution = model.createProperty(uqNS + "hasDistribution");
        Resource sensor1 = model.createResource(exNS + "sensor1");
        Resource sensor2 = model.createResource(exNS + "sensor2");
        
        // Full covariance
        String gmm1Json = "{\"n_components\":1,\"dimensions\":1,\"covariance_type\":\"full\"," +
                         "\"weights\":[1.0],\"means\":[[5.0]],\"covariances\":[[[1.0]]]}";
        
        // Diagonal covariance (same distribution)
        String gmm2Json = "{\"n_components\":1,\"dimensions\":1,\"covariance_type\":\"diag\"," +
                         "\"weights\":[1.0],\"means\":[[5.0]],\"covariances\":[[1.0]]}";
        
        sensor1.addProperty(hasDistribution, 
                           model.createTypedLiteral(gmm1Json, GMMDatatype.INSTANCE));
        sensor2.addProperty(hasDistribution, 
                           model.createTypedLiteral(gmm2Json, GMMDatatype.INSTANCE));
        
        String queryString = 
            "PREFIX prob: <http://probsparql.org/function#> \n" +
            "PREFIX uq: <http://example.org/ontology/uncertainty#> \n" +
            "PREFIX ex: <http://example.org/data/> \n" +
            "SELECT ?kl ?js WHERE { \n" +
            "  ex:sensor1 uq:hasDistribution ?gmm1 . \n" +
            "  ex:sensor2 uq:hasDistribution ?gmm2 . \n" +
            "  BIND(prob:kldivergence(?gmm1, ?gmm2) AS ?kl) \n" +
            "  BIND(prob:jsdivergence(?gmm1, ?gmm2) AS ?js) \n" +
            "}";
        
        Query query = QueryFactory.create(queryString);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext());
            QuerySolution soln = results.next();
            double kl = soln.getLiteral("kl").getDouble();
            double js = soln.getLiteral("js").getDouble();
            
            // Should handle different covariance types
            assertEquals(0.0, kl, 0.05, 
                        "KL should be ~0 for equivalent distributions");
            assertEquals(0.0, js, 0.05, 
                        "JS should be ~0 for equivalent distributions");
        }
    }

    @Test
    void testSameTermAndSameDistributionForPermutedGMMs() {
        Model model = ModelFactory.createDefaultModel();

        String uqNS = "http://example.org/ontology/uncertainty#";
        String exNS = "http://example.org/data/";

        Property hasDistribution = model.createProperty(uqNS + "hasDistribution");
        Resource left = model.createResource(exNS + "left");
        Resource right = model.createResource(exNS + "right");

        String g1Json = "{\"n_components\":2,\"dimensions\":1,\"covariance_type\":\"full\","
            + "\"weights\":[0.3,0.7],\"means\":[[1.0],[5.0]],\"covariances\":[[[1.0]],[[2.0]]]}";
        String g2Json = "{\"n_components\":2,\"dimensions\":1,\"covariance_type\":\"full\","
            + "\"weights\":[0.7,0.3],\"means\":[[5.0],[1.0]],\"covariances\":[[[2.0]],[[1.0]]]}";

        Literal g1 = model.createTypedLiteral(g1Json, GMMDatatype.INSTANCE);
        Literal g2 = model.createTypedLiteral(g2Json, GMMDatatype.INSTANCE);

        left.addProperty(hasDistribution, g1);
        right.addProperty(hasDistribution, g2);

        assertEquals(g1Json, g1.getLexicalForm(), "GMM lexical form should preserve the original component order");
        assertEquals(g2Json, g2.getLexicalForm(), "GMM lexical form should preserve the original component order");

        NodeValue leftNodeValue = NodeValue.makeNode(g1.asNode());
        NodeValue rightNodeValue = NodeValue.makeNode(g2.asNode());

        assertFalse(NodeFunctions.sameTerm(g1.asNode(), g2.asNode()),
            "RDF-term identity should stay sensitive to component order");
        assertFalse(new SameTerm().exec(leftNodeValue, rightNodeValue).getBoolean(),
            "prob:sameTerm should reflect RDF-term identity");
        assertTrue(new SameDistribution().exec(leftNodeValue, rightNodeValue).getBoolean(),
            "prob:sameDistribution should compare parsed distribution values");
        assertTrue(g1.asNode().sameValueAs(g2.asNode()),
            "Node.sameValueAs should continue to use datatype value equality");
    }

    @Test
    void testSameDistributionForDirichletValues() {
        Model model = ModelFactory.createDefaultModel();

        String uqNS = "http://example.org/ontology/uncertainty#";
        String exNS = "http://example.org/data/";

        Property hasDistribution = model.createProperty(uqNS + "hasDistribution");
        Resource left = model.createResource(exNS + "dir1");
        Resource right = model.createResource(exNS + "dir2");

        DirichletValue dir1 = new DirichletValue(new double[]{2.5, 1.0, 3.0});
        DirichletValue dir2 = new DirichletValue(new double[]{2.5, 1.0, 3.0});

        left.addProperty(hasDistribution, model.createTypedLiteral(dir1.toJSON(), DirichletDatatype.INSTANCE));
        right.addProperty(hasDistribution, model.createTypedLiteral(dir2.toJSON(), DirichletDatatype.INSTANCE));

        String queryString =
            "PREFIX prob: <http://probsparql.org/function#> \n" +
            "PREFIX uq: <http://example.org/ontology/uncertainty#> \n" +
            "PREFIX ex: <http://example.org/data/> \n" +
            "SELECT ?sameDistribution ?equals WHERE { \n" +
            "  ex:dir1 uq:hasDistribution ?d1 . \n" +
            "  ex:dir2 uq:hasDistribution ?d2 . \n" +
            "  BIND(prob:sameDistribution(?d1, ?d2) AS ?sameDistribution) \n" +
            "  BIND((?d1 = ?d2) AS ?equals) \n" +
            "}";

        Query query = QueryFactory.create(queryString);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();

            assertTrue(results.hasNext());
            QuerySolution soln = results.next();
            assertTrue(soln.getLiteral("sameDistribution").getBoolean(),
                "prob:sameDistribution should work for Dirichlet values");
            assertTrue(soln.getLiteral("equals").getBoolean(),
                "SPARQL '=' should work for Dirichlet values after adding equals/hashCode");
        }
    }
}
