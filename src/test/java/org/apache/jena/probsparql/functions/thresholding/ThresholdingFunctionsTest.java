package org.apache.jena.probsparql.functions.thresholding;

import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CDF, LogPDF, and LogCDF functions.
 * 
 * @author ProbSPARQL Team
 */
class ThresholdingFunctionsTest {
    
    @BeforeAll
    static void setUp() {
        ProbSPARQL.init();
    }
    
    @Test
    void testCDF_SingleGaussian() {
        Model model = ModelFactory.createDefaultModel();
        
        String uqNS = "http://example.org/ontology/uncertainty#";
        String exNS = "http://example.org/data/";
        
        Property hasDistribution = model.createProperty(uqNS + "hasDistribution");
        Resource sensor = model.createResource(exNS + "sensor1");
        
        // N(6.0, 0.16)
        String gmmJson = "{\"n_components\":1,\"dimensions\":1,\"covariance_type\":\"full\"," +
                        "\"weights\":[1.0],\"means\":[[6.0]],\"covariances\":[[[0.16]]]}";
        
        Literal gmmLiteral = model.createTypedLiteral(gmmJson, GMMDatatype.INSTANCE);
        sensor.addProperty(hasDistribution, gmmLiteral);
        
        String queryString = 
            "PREFIX prob: <http://probsparql.org/function#> \n" +
            "PREFIX uq: <http://example.org/ontology/uncertainty#> \n" +
            "PREFIX ex: <http://example.org/data/> \n" +
            "SELECT ?cdf WHERE { \n" +
            "  ex:sensor1 uq:hasDistribution ?gmm . \n" +
            "  BIND(prob:cdf(?gmm, 6.0) AS ?cdf) \n" +
            "}";
        
        Query query = QueryFactory.create(queryString);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext());
            QuerySolution soln = results.next();
            double cdf = soln.getLiteral("cdf").getDouble();
            
            // At mean, CDF should be 0.5
            assertEquals(0.5, cdf, 0.01, "CDF at mean should be 0.5");
        }
    }
    
    @Test
    void testCDF_RangeCheck() {
        Model model = ModelFactory.createDefaultModel();
        
        String uqNS = "http://example.org/ontology/uncertainty#";
        String exNS = "http://example.org/data/";
        
        Property hasDistribution = model.createProperty(uqNS + "hasDistribution");
        Resource sensor = model.createResource(exNS + "sensor1");
        
        String gmmJson = "{\"n_components\":1,\"dimensions\":1,\"covariance_type\":\"full\"," +
                        "\"weights\":[1.0],\"means\":[[6.0]],\"covariances\":[[[1.0]]]}";
        
        sensor.addProperty(hasDistribution, 
                          model.createTypedLiteral(gmmJson, GMMDatatype.INSTANCE));
        
        String queryString = 
            "PREFIX prob: <http://probsparql.org/function#> \n" +
            "PREFIX uq: <http://example.org/ontology/uncertainty#> \n" +
            "PREFIX ex: <http://example.org/data/> \n" +
            "SELECT ?point ?cdf WHERE { \n" +
            "  ex:sensor1 uq:hasDistribution ?gmm . \n" +
            "  VALUES ?point { 4.0 6.0 8.0 } \n" +
            "  BIND(prob:cdf(?gmm, ?point) AS ?cdf) \n" +
            "} ORDER BY ?point";
        
        Query query = QueryFactory.create(queryString);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            
            // CDF should be increasing
            double prevCDF = 0.0;
            while (results.hasNext()) {
                QuerySolution soln = results.next();
                double cdf = soln.getLiteral("cdf").getDouble();
                assertTrue(cdf >= prevCDF, "CDF should be monotonically increasing");
                assertTrue(cdf >= 0.0 && cdf <= 1.0, "CDF should be in [0, 1]");
                prevCDF = cdf;
            }
        }
    }
    
    @Test
    void testLogPDF_vs_PDF() {
        Model model = ModelFactory.createDefaultModel();
        
        String uqNS = "http://example.org/ontology/uncertainty#";
        String exNS = "http://example.org/data/";
        
        Property hasDistribution = model.createProperty(uqNS + "hasDistribution");
        Resource sensor = model.createResource(exNS + "sensor1");
        
        String gmmJson = "{\"n_components\":1,\"dimensions\":1,\"covariance_type\":\"full\"," +
                        "\"weights\":[1.0],\"means\":[[6.0]],\"covariances\":[[[0.25]]]}";
        
        sensor.addProperty(hasDistribution, 
                          model.createTypedLiteral(gmmJson, GMMDatatype.INSTANCE));
        
        String queryString = 
            "PREFIX prob: <http://probsparql.org/function#> \n" +
            "PREFIX uq: <http://example.org/ontology/uncertainty#> \n" +
            "PREFIX ex: <http://example.org/data/> \n" +
            "SELECT ?pdf ?logpdf WHERE { \n" +
            "  ex:sensor1 uq:hasDistribution ?gmm . \n" +
            "  BIND(prob:pdf(?gmm, 6.0) AS ?pdf) \n" +
            "  BIND(prob:logpdf(?gmm, 6.0) AS ?logpdf) \n" +
            "}";
        
        Query query = QueryFactory.create(queryString);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext());
            QuerySolution soln = results.next();
            double pdf = soln.getLiteral("pdf").getDouble();
            double logpdf = soln.getLiteral("logpdf").getDouble();
            
            // log(PDF) should equal LogPDF
            assertEquals(Math.log(pdf), logpdf, 1e-6, 
                        "log(PDF) should equal LogPDF");
        }
    }
    
    @Test
    void testLogCDF_vs_CDF() {
        Model model = ModelFactory.createDefaultModel();
        
        String uqNS = "http://example.org/ontology/uncertainty#";
        String exNS = "http://example.org/data/";
        
        Property hasDistribution = model.createProperty(uqNS + "hasDistribution");
        Resource sensor = model.createResource(exNS + "sensor1");
        
        String gmmJson = "{\"n_components\":1,\"dimensions\":1,\"covariance_type\":\"full\"," +
                        "\"weights\":[1.0],\"means\":[[5.0]],\"covariances\":[[[0.5]]]}";
        
        sensor.addProperty(hasDistribution, 
                          model.createTypedLiteral(gmmJson, GMMDatatype.INSTANCE));
        
        String queryString = 
            "PREFIX prob: <http://probsparql.org/function#> \n" +
            "PREFIX uq: <http://example.org/ontology/uncertainty#> \n" +
            "PREFIX ex: <http://example.org/data/> \n" +
            "SELECT ?cdf ?logcdf WHERE { \n" +
            "  ex:sensor1 uq:hasDistribution ?gmm . \n" +
            "  BIND(prob:cdf(?gmm, 5.5) AS ?cdf) \n" +
            "  BIND(prob:logcdf(?gmm, 5.5) AS ?logcdf) \n" +
            "}";
        
        Query query = QueryFactory.create(queryString);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext());
            QuerySolution soln = results.next();
            double cdf = soln.getLiteral("cdf").getDouble();
            double logcdf = soln.getLiteral("logcdf").getDouble();
            
            // log(CDF) should equal LogCDF
            assertEquals(Math.log(cdf), logcdf, 1e-6, 
                        "log(CDF) should equal LogCDF");
        }
    }
    
    @Test
    void testLogPDF_BimodalGMM() {
        Model model = ModelFactory.createDefaultModel();
        
        String uqNS = "http://example.org/ontology/uncertainty#";
        String exNS = "http://example.org/data/";
        
        Property hasDistribution = model.createProperty(uqNS + "hasDistribution");
        Resource sensor = model.createResource(exNS + "sensor2");
        
        // Bimodal GMM
        String gmmJson = "{\"n_components\":2,\"dimensions\":1,\"covariance_type\":\"full\"," +
                        "\"weights\":[0.6,0.4]," +
                        "\"means\":[[5.0],[8.0]]," +
                        "\"covariances\":[[[0.5]],[[0.5]]]}";
        
        sensor.addProperty(hasDistribution, 
                          model.createTypedLiteral(gmmJson, GMMDatatype.INSTANCE));
        
        String queryString = 
            "PREFIX prob: <http://probsparql.org/function#> \n" +
            "PREFIX uq: <http://example.org/ontology/uncertainty#> \n" +
            "PREFIX ex: <http://example.org/data/> \n" +
            "SELECT ?logpdf WHERE { \n" +
            "  ex:sensor2 uq:hasDistribution ?gmm . \n" +
            "  BIND(prob:logpdf(?gmm, 6.5) AS ?logpdf) \n" +
            "}";
        
        Query query = QueryFactory.create(queryString);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext());
            QuerySolution soln = results.next();
            double logpdf = soln.getLiteral("logpdf").getDouble();
            
            // LogPDF should be finite (not -infinity)
            assertFalse(Double.isInfinite(logpdf), "LogPDF should be finite");
            assertTrue(logpdf < 0, "LogPDF should be negative (since PDF < 1)");
        }
    }
    
    @Test
    void testAllFunctions_DiagonalCovariance() {
        Model model = ModelFactory.createDefaultModel();
        
        String uqNS = "http://example.org/ontology/uncertainty#";
        String exNS = "http://example.org/data/";
        
        Property hasDistribution = model.createProperty(uqNS + "hasDistribution");
        Resource sensor = model.createResource(exNS + "sensor3");
        
        String gmmJson = "{\"n_components\":1,\"dimensions\":1,\"covariance_type\":\"diag\"," +
                        "\"weights\":[1.0],\"means\":[[6.0]],\"covariances\":[[0.25]]}";
        
        sensor.addProperty(hasDistribution, 
                          model.createTypedLiteral(gmmJson, GMMDatatype.INSTANCE));
        
        String queryString = 
            "PREFIX prob: <http://probsparql.org/function#> \n" +
            "PREFIX uq: <http://example.org/ontology/uncertainty#> \n" +
            "PREFIX ex: <http://example.org/data/> \n" +
            "SELECT ?pdf ?cdf ?logpdf ?logcdf WHERE { \n" +
            "  ex:sensor3 uq:hasDistribution ?gmm . \n" +
            "  BIND(prob:pdf(?gmm, 6.0) AS ?pdf) \n" +
            "  BIND(prob:cdf(?gmm, 6.0) AS ?cdf) \n" +
            "  BIND(prob:logpdf(?gmm, 6.0) AS ?logpdf) \n" +
            "  BIND(prob:logcdf(?gmm, 6.0) AS ?logcdf) \n" +
            "}";
        
        Query query = QueryFactory.create(queryString);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext());
            QuerySolution soln = results.next();
            
            // All functions should work with diagonal covariance
            assertTrue(soln.getLiteral("pdf").getDouble() > 0);
            assertEquals(0.5, soln.getLiteral("cdf").getDouble(), 0.01);
            assertTrue(soln.getLiteral("logpdf").getDouble() < 0);
            assertTrue(soln.getLiteral("logcdf").getDouble() < 0);
        }
    }
}
