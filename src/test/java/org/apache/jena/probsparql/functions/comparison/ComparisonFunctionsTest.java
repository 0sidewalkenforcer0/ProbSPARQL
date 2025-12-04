package org.apache.jena.probsparql.functions.comparison;

import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.probsparql.datatypes.GMMDatatype;
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
        String gmmJson = "{\"K\":1,\"d\":1,\"covariance_type\":\"full\"," +
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
        String gmm1Json = "{\"K\":1,\"d\":1,\"covariance_type\":\"full\"," +
                         "\"weights\":[1.0],\"means\":[[5.0]],\"covariances\":[[[1.0]]]}";
        
        // GMM2: N(6.0, 2.0)
        String gmm2Json = "{\"K\":1,\"d\":1,\"covariance_type\":\"full\"," +
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
        
        String gmmJson = "{\"K\":1,\"d\":1,\"covariance_type\":\"full\"," +
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
        
        String gmm1Json = "{\"K\":1,\"d\":1,\"covariance_type\":\"full\"," +
                         "\"weights\":[1.0],\"means\":[[5.0]],\"covariances\":[[[1.0]]]}";
        
        String gmm2Json = "{\"K\":1,\"d\":1,\"covariance_type\":\"full\"," +
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
        String gmm1Json = "{\"K\":1,\"d\":1,\"covariance_type\":\"full\"," +
                         "\"weights\":[1.0],\"means\":[[0.0]],\"covariances\":[[[0.1]]]}";
        
        String gmm2Json = "{\"K\":1,\"d\":1,\"covariance_type\":\"full\"," +
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
        String gmm1Json = "{\"K\":2,\"d\":1,\"covariance_type\":\"full\"," +
                         "\"weights\":[0.5,0.5]," +
                         "\"means\":[[2.0],[8.0]]," +
                         "\"covariances\":[[[0.5]],[[0.5]]]}";
        
        // Bimodal GMM2 (different modes)
        String gmm2Json = "{\"K\":2,\"d\":1,\"covariance_type\":\"full\"," +
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
        String gmm1Json = "{\"K\":1,\"d\":1,\"covariance_type\":\"full\"," +
                         "\"weights\":[1.0],\"means\":[[5.0]],\"covariances\":[[[1.0]]]}";
        
        // Diagonal covariance (same distribution)
        String gmm2Json = "{\"K\":1,\"d\":1,\"covariance_type\":\"diag\"," +
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
}
