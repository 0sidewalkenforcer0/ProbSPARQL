package org.apache.jena.probsparql.functions.transformation;

import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for transformation functions.
 * 
 * @author ProbSPARQL Team
 */
class TransformationFunctionsTest {
    
    @BeforeAll
    static void setUp() {
        ProbSPARQL.init();
    }
    
    @Test
    void testScale_BasicScaling() {
        Model model = ModelFactory.createDefaultModel();
        
        String uqNS = "http://example.org/ontology/uncertainty#";
        String exNS = "http://example.org/data/";
        
        Property hasDistribution = model.createProperty(uqNS + "hasDistribution");
        Resource sensor = model.createResource(exNS + "sensor1");
        
        // N(5.0, 1.0)
        String gmmJson = "{\"n_components\":1,\"dimensions\":1,\"covariance_type\":\"full\"," +
                        "\"weights\":[1.0],\"means\":[[5.0]],\"covariances\":[[[1.0]]]}";
        
        sensor.addProperty(hasDistribution, 
                          model.createTypedLiteral(gmmJson, GMMDatatype.INSTANCE));
        
        String queryString = 
            "PREFIX prob: <http://probsparql.org/function#> \n" +
            "PREFIX uq: <http://example.org/ontology/uncertainty#> \n" +
            "PREFIX ex: <http://example.org/data/> \n" +
            "SELECT ?scaledDist WHERE { \n" +
            "  ex:sensor1 uq:hasDistribution ?gmm . \n" +
            "  BIND(prob:scale(?gmm, 2.0) AS ?scaledDist) \n" +
            "}";
        
        Query query = QueryFactory.create(queryString);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext());
            QuerySolution soln = results.next();
            Literal scaledLiteral = soln.getLiteral("scaledDist");
            
            GMMValue scaled = (GMMValue) scaledLiteral.getValue();
            
            // Mean should be 2.0 * 5.0 = 10.0
            assertEquals(10.0, scaled.getMeans()[0][0], 1e-6);
            
            // Variance should be 2.0² * 1.0 = 4.0
            assertEquals(4.0, scaled.getCovariances()[0][0][0], 1e-6);
        }
    }
    
    @Test
    void testShift_BasicShifting() {
        Model model = ModelFactory.createDefaultModel();
        
        String uqNS = "http://example.org/ontology/uncertainty#";
        String exNS = "http://example.org/data/";
        
        Property hasDistribution = model.createProperty(uqNS + "hasDistribution");
        Resource sensor = model.createResource(exNS + "sensor1");
        
        String gmmJson = "{\"n_components\":1,\"dimensions\":1,\"covariance_type\":\"full\"," +
                        "\"weights\":[1.0],\"means\":[[5.0]],\"covariances\":[[[1.0]]]}";
        
        sensor.addProperty(hasDistribution, 
                          model.createTypedLiteral(gmmJson, GMMDatatype.INSTANCE));
        
        String queryString = 
            "PREFIX prob: <http://probsparql.org/function#> \n" +
            "PREFIX uq: <http://example.org/ontology/uncertainty#> \n" +
            "PREFIX ex: <http://example.org/data/> \n" +
            "SELECT ?shiftedDist WHERE { \n" +
            "  ex:sensor1 uq:hasDistribution ?gmm . \n" +
            "  BIND(prob:shift(?gmm, 3.0) AS ?shiftedDist) \n" +
            "}";
        
        Query query = QueryFactory.create(queryString);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext());
            QuerySolution soln = results.next();
            Literal shiftedLiteral = soln.getLiteral("shiftedDist");
            
            GMMValue shifted = (GMMValue) shiftedLiteral.getValue();
            
            // Mean should be 5.0 + 3.0 = 8.0
            assertEquals(8.0, shifted.getMeans()[0][0], 1e-6);
            
            // Variance unchanged
            assertEquals(1.0, shifted.getCovariances()[0][0][0], 1e-6);
        }
    }
    
    @Test
    void testLinearTransform_Combined() {
        Model model = ModelFactory.createDefaultModel();
        
        String uqNS = "http://example.org/ontology/uncertainty#";
        String exNS = "http://example.org/data/";
        
        Property hasDistribution = model.createProperty(uqNS + "hasDistribution");
        Resource sensor = model.createResource(exNS + "sensor1");
        
        String gmmJson = "{\"n_components\":1,\"dimensions\":1,\"covariance_type\":\"full\"," +
                        "\"weights\":[1.0],\"means\":[[4.0]],\"covariances\":[[[2.0]]]}";
        
        sensor.addProperty(hasDistribution, 
                          model.createTypedLiteral(gmmJson, GMMDatatype.INSTANCE));
        
        String queryString = 
            "PREFIX prob: <http://probsparql.org/function#> \n" +
            "PREFIX uq: <http://example.org/ontology/uncertainty#> \n" +
            "PREFIX ex: <http://example.org/data/> \n" +
            "SELECT ?transformedDist WHERE { \n" +
            "  ex:sensor1 uq:hasDistribution ?gmm . \n" +
            "  BIND(prob:linearTransform(?gmm, 3.0, 2.0) AS ?transformedDist) \n" +
            "}";
        
        Query query = QueryFactory.create(queryString);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext());
            QuerySolution soln = results.next();
            Literal transformedLiteral = soln.getLiteral("transformedDist");
            
            GMMValue transformed = (GMMValue) transformedLiteral.getValue();
            
            // Y = 3*X + 2, X ~ N(4, 2)
            // E[Y] = 3*4 + 2 = 14
            assertEquals(14.0, transformed.getMeans()[0][0], 1e-6);
            
            // Var[Y] = 3² * 2 = 18
            assertEquals(18.0, transformed.getCovariances()[0][0][0], 1e-6);
        }
    }
    
    @Test
    void testConvolve_SumOfIndependent() {
        Model model = ModelFactory.createDefaultModel();
        
        String uqNS = "http://example.org/ontology/uncertainty#";
        String exNS = "http://example.org/data/";
        
        Property hasDistribution = model.createProperty(uqNS + "hasDistribution");
        Resource sensor1 = model.createResource(exNS + "sensor1");
        Resource sensor2 = model.createResource(exNS + "sensor2");
        
        // X ~ N(3, 1)
        String gmm1Json = "{\"n_components\":1,\"dimensions\":1,\"covariance_type\":\"full\"," +
                         "\"weights\":[1.0],\"means\":[[3.0]],\"covariances\":[[[1.0]]]}";
        
        // Y ~ N(5, 2)
        String gmm2Json = "{\"n_components\":1,\"dimensions\":1,\"covariance_type\":\"full\"," +
                         "\"weights\":[1.0],\"means\":[[5.0]],\"covariances\":[[[2.0]]]}";
        
        sensor1.addProperty(hasDistribution, 
                           model.createTypedLiteral(gmm1Json, GMMDatatype.INSTANCE));
        sensor2.addProperty(hasDistribution, 
                           model.createTypedLiteral(gmm2Json, GMMDatatype.INSTANCE));
        
        String queryString = 
            "PREFIX prob: <http://probsparql.org/function#> \n" +
            "PREFIX uq: <http://example.org/ontology/uncertainty#> \n" +
            "PREFIX ex: <http://example.org/data/> \n" +
            "SELECT ?sumDist WHERE { \n" +
            "  ex:sensor1 uq:hasDistribution ?gmm1 . \n" +
            "  ex:sensor2 uq:hasDistribution ?gmm2 . \n" +
            "  BIND(prob:convolve(?gmm1, ?gmm2) AS ?sumDist) \n" +
            "}";
        
        Query query = QueryFactory.create(queryString);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext());
            QuerySolution soln = results.next();
            Literal sumLiteral = soln.getLiteral("sumDist");
            
            GMMValue sum = (GMMValue) sumLiteral.getValue();
            
            // Z = X + Y: E[Z] = E[X] + E[Y] = 3 + 5 = 8
            assertEquals(8.0, sum.getMeans()[0][0], 1e-6);
            
            // Var[Z] = Var[X] + Var[Y] = 1 + 2 = 3
            assertEquals(3.0, sum.getCovariances()[0][0][0], 1e-6);
        }
    }
    
    @Test
    void testConvolve_BimodalGMMs() {
        Model model = ModelFactory.createDefaultModel();
        
        String uqNS = "http://example.org/ontology/uncertainty#";
        String exNS = "http://example.org/data/";
        
        Property hasDistribution = model.createProperty(uqNS + "hasDistribution");
        Resource sensor1 = model.createResource(exNS + "sensor1");
        Resource sensor2 = model.createResource(exNS + "sensor2");
        
        // Bimodal GMM with K=2
        String gmm1Json = "{\"n_components\":2,\"dimensions\":1,\"covariance_type\":\"full\"," +
                         "\"weights\":[0.6,0.4]," +
                         "\"means\":[[2.0],[6.0]]," +
                         "\"covariances\":[[[0.5]],[[0.5]]]}";
        
        String gmm2Json = "{\"n_components\":2,\"dimensions\":1,\"covariance_type\":\"full\"," +
                         "\"weights\":[0.7,0.3]," +
                         "\"means\":[[1.0],[4.0]]," +
                         "\"covariances\":[[[0.3]],[[0.3]]]}";
        
        sensor1.addProperty(hasDistribution, 
                           model.createTypedLiteral(gmm1Json, GMMDatatype.INSTANCE));
        sensor2.addProperty(hasDistribution, 
                           model.createTypedLiteral(gmm2Json, GMMDatatype.INSTANCE));
        
        String queryString = 
            "PREFIX prob: <http://probsparql.org/function#> \n" +
            "PREFIX uq: <http://example.org/ontology/uncertainty#> \n" +
            "PREFIX ex: <http://example.org/data/> \n" +
            "SELECT ?sumDist WHERE { \n" +
            "  ex:sensor1 uq:hasDistribution ?gmm1 . \n" +
            "  ex:sensor2 uq:hasDistribution ?gmm2 . \n" +
            "  BIND(prob:convolve(?gmm1, ?gmm2) AS ?sumDist) \n" +
            "}";
        
        Query query = QueryFactory.create(queryString);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext());
            QuerySolution soln = results.next();
            Literal sumLiteral = soln.getLiteral("sumDist");
            
            GMMValue sum = (GMMValue) sumLiteral.getValue();
            
            // Should have K1 * K2 = 2 * 2 = 4 components
            assertEquals(4, sum.getNComponents());
            
            // Verify weights sum to 1
            double weightSum = 0.0;
            for (double w : sum.getWeights()) {
                weightSum += w;
            }
            assertEquals(1.0, weightSum, 1e-6);
        }
    }
    
    @Test
    void testMarginal_OneDimensional() {
        Model model = ModelFactory.createDefaultModel();
        
        String uqNS = "http://example.org/ontology/uncertainty#";
        String exNS = "http://example.org/data/";
        
        Property hasDistribution = model.createProperty(uqNS + "hasDistribution");
        Resource sensor = model.createResource(exNS + "sensor1");
        
        String gmmJson = "{\"n_components\":1,\"dimensions\":1,\"covariance_type\":\"full\"," +
                        "\"weights\":[1.0],\"means\":[[5.0]],\"covariances\":[[[2.0]]]}";
        
        sensor.addProperty(hasDistribution, 
                          model.createTypedLiteral(gmmJson, GMMDatatype.INSTANCE));
        
        String queryString = 
            "PREFIX prob: <http://probsparql.org/function#> \n" +
            "PREFIX uq: <http://example.org/ontology/uncertainty#> \n" +
            "PREFIX ex: <http://example.org/data/> \n" +
            "SELECT ?marginalDist WHERE { \n" +
            "  ex:sensor1 uq:hasDistribution ?gmm . \n" +
            "  BIND(prob:marginal(?gmm, 0) AS ?marginalDist) \n" +
            "}";
        
        Query query = QueryFactory.create(queryString);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext());
            QuerySolution soln = results.next();
            Literal marginalLiteral = soln.getLiteral("marginalDist");
            
            GMMValue marginal = (GMMValue) marginalLiteral.getValue();
            
            // For 1D, marginal should be identical to original
            assertEquals(5.0, marginal.getMeans()[0][0], 1e-6);
            assertEquals(2.0, marginal.getCovariances()[0][0][0], 1e-6);
        }
    }
    
    @Test
    void testJoint_IndependentGMMs() {
        Model model = ModelFactory.createDefaultModel();
        
        String uqNS = "http://example.org/ontology/uncertainty#";
        String exNS = "http://example.org/data/";
        
        Property hasDistribution = model.createProperty(uqNS + "hasDistribution");
        Resource sensor1 = model.createResource(exNS + "sensor1");
        Resource sensor2 = model.createResource(exNS + "sensor2");
        
        // X ~ N(3, 1)
        String gmm1Json = "{\"n_components\":1,\"dimensions\":1,\"covariance_type\":\"full\"," +
                         "\"weights\":[1.0],\"means\":[[3.0]],\"covariances\":[[[1.0]]]}";
        
        // Y ~ N(5, 2)
        String gmm2Json = "{\"n_components\":1,\"dimensions\":1,\"covariance_type\":\"full\"," +
                         "\"weights\":[1.0],\"means\":[[5.0]],\"covariances\":[[[2.0]]]}";
        
        sensor1.addProperty(hasDistribution, 
                           model.createTypedLiteral(gmm1Json, GMMDatatype.INSTANCE));
        sensor2.addProperty(hasDistribution, 
                           model.createTypedLiteral(gmm2Json, GMMDatatype.INSTANCE));
        
        String queryString = 
            "PREFIX prob: <http://probsparql.org/function#> \n" +
            "PREFIX uq: <http://example.org/ontology/uncertainty#> \n" +
            "PREFIX ex: <http://example.org/data/> \n" +
            "SELECT ?jointDist WHERE { \n" +
            "  ex:sensor1 uq:hasDistribution ?gmm1 . \n" +
            "  ex:sensor2 uq:hasDistribution ?gmm2 . \n" +
            "  BIND(prob:joint(?gmm1, ?gmm2) AS ?jointDist) \n" +
            "}";
        
        Query query = QueryFactory.create(queryString);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext());
            QuerySolution soln = results.next();
            Literal jointLiteral = soln.getLiteral("jointDist");
            
            GMMValue joint = (GMMValue) jointLiteral.getValue();
            
            // Should be 2-dimensional
            assertEquals(2, joint.getDimensions());
            
            // Mean should be [3, 5]
            assertEquals(3.0, joint.getMeans()[0][0], 1e-6);
            assertEquals(5.0, joint.getMeans()[0][1], 1e-6);
            
            // Covariance should be block diagonal
            assertEquals(1.0, joint.getCovariances()[0][0][0], 1e-6); // Var[X]
            assertEquals(2.0, joint.getCovariances()[0][1][1], 1e-6); // Var[Y]
            assertEquals(0.0, joint.getCovariances()[0][0][1], 1e-6); // Cov[X,Y] = 0
        }
    }
    
    @Test
    void testTransformationChaining() {
        Model model = ModelFactory.createDefaultModel();
        
        String uqNS = "http://example.org/ontology/uncertainty#";
        String exNS = "http://example.org/data/";
        
        Property hasDistribution = model.createProperty(uqNS + "hasDistribution");
        Resource sensor = model.createResource(exNS + "sensor1");
        
        String gmmJson = "{\"n_components\":1,\"dimensions\":1,\"covariance_type\":\"full\"," +
                        "\"weights\":[1.0],\"means\":[[2.0]],\"covariances\":[[[1.0]]]}";
        
        sensor.addProperty(hasDistribution, 
                          model.createTypedLiteral(gmmJson, GMMDatatype.INSTANCE));
        
        // Chain: scale by 2, then shift by 3
        String queryString = 
            "PREFIX prob: <http://probsparql.org/function#> \n" +
            "PREFIX uq: <http://example.org/ontology/uncertainty#> \n" +
            "PREFIX ex: <http://example.org/data/> \n" +
            "SELECT ?finalDist WHERE { \n" +
            "  ex:sensor1 uq:hasDistribution ?gmm . \n" +
            "  BIND(prob:scale(?gmm, 2.0) AS ?scaled) \n" +
            "  BIND(prob:shift(?scaled, 3.0) AS ?finalDist) \n" +
            "}";
        
        Query query = QueryFactory.create(queryString);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext());
            QuerySolution soln = results.next();
            Literal finalLiteral = soln.getLiteral("finalDist");
            
            GMMValue final_ = (GMMValue) finalLiteral.getValue();
            
            // Y = 2*X + 3, X ~ N(2, 1)
            // E[Y] = 2*2 + 3 = 7
            assertEquals(7.0, final_.getMeans()[0][0], 1e-6);
            
            // Var[Y] = 4 * 1 = 4
            assertEquals(4.0, final_.getCovariances()[0][0][0], 1e-6);
        }
    }
}
