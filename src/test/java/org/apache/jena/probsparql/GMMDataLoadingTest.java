package org.apache.jena.probsparql;

import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for loading RDF data with GMM literals.
 * 
 * Tests the complete workflow:
 * 1. Initialize ProbSPARQL
 * 2. Load TTL files with custom GMM datatype
 * 3. Query and extract GMM values
 * 4. Validate parsed GMM properties
 * 
 * @author ProbSPARQL Team
 */
class GMMDataLoadingTest {
    
    private static final String DATA_FILE = "examples/data/angle-grinder-instances.ttl";
    
    @BeforeAll
    static void setUp() {
        // Initialize ProbSPARQL to register custom datatypes
        ProbSPARQL.init();
    }
    
    @Test
    void testLoadAngleGrinderData() {
        String dataPath = Paths.get(DATA_FILE).toAbsolutePath().toString();
        
        // Load the RDF data
        Model model = RDFDataMgr.loadModel(dataPath);
        
        assertNotNull(model);
        assertFalse(model.isEmpty(), "Model should not be empty");
        
        // Print model statistics
        System.out.println("Model size: " + model.size() + " triples");
    }
    
    @Test
    void testExtractGMMFromRDF() {
        String dataPath = Paths.get(DATA_FILE).toAbsolutePath().toString();
        Model model = RDFDataMgr.loadModel(dataPath);
        
        // Define namespaces
        String uqNS = "http://example.org/ontology/uncertainty#";
        String exNS = "http://example.org/data/";
        
        Property hasDistribution = model.createProperty(uqNS + "hasDistribution");
        Resource rv_toothlength_001 = model.createResource(exNS + "rv_toothlength_001");
        
        // Get the GMM literal
        Statement stmt = rv_toothlength_001.getProperty(hasDistribution);
        assertNotNull(stmt, "Should have hasDistribution property");
        
        Literal gmmLiteral = stmt.getLiteral();
        assertNotNull(gmmLiteral);
        assertEquals(GMMDatatype.URI, gmmLiteral.getDatatypeURI());
        
        // Parse the GMM value
        Object value = gmmLiteral.getValue();
        assertInstanceOf(GMMValue.class, value);
        
        GMMValue gmm = (GMMValue) value;
        assertEquals(1, gmm.getK(), "Should have 1 component (single Gaussian)");
        assertEquals(1, gmm.getD(), "Should be 1-dimensional");
        assertEquals("full", gmm.getCovarianceType());
        
        double[] weights = gmm.getWeights();
        assertArrayEquals(new double[]{1.0}, weights, 1e-9);
        
        System.out.println("Successfully extracted GMM: " + gmm);
    }
    
    @Test
    void testExtractAllRandomVariables() {
        String dataPath = Paths.get(DATA_FILE).toAbsolutePath().toString();
        Model model = RDFDataMgr.loadModel(dataPath);
        
        String uqNS = "http://example.org/ontology/uncertainty#";
        
        Resource RandomVariable = model.createResource(uqNS + "RandomVariable");
        Property hasDistribution = model.createProperty(uqNS + "hasDistribution");
        
        // Find all RandomVariable instances
        ResIterator iter = model.listSubjectsWithProperty(
            model.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            RandomVariable
        );
        
        int count = 0;
        while (iter.hasNext()) {
            Resource rv = iter.nextResource();
            Statement distStmt = rv.getProperty(hasDistribution);
            
            if (distStmt != null) {
                Literal gmmLit = distStmt.getLiteral();
                assertEquals(GMMDatatype.URI, gmmLit.getDatatypeURI());
                
                GMMValue gmm = (GMMValue) gmmLit.getValue();
                assertNotNull(gmm);
                
                System.out.println("Random Variable: " + rv.getLocalName());
                System.out.println("  K=" + gmm.getK() + ", d=" + gmm.getD());
                System.out.println("  Covariance type: " + gmm.getCovarianceType());
                
                count++;
            }
        }
        
        assertEquals(8, count, "Should have exactly 8 random variables");
    }
    
    @Test
    void testBimodalDistribution() {
        String dataPath = Paths.get(DATA_FILE).toAbsolutePath().toString();
        Model model = RDFDataMgr.loadModel(dataPath);
        
        String exNS = "http://example.org/data/";
        String uqNS = "http://example.org/ontology/uncertainty#";
        
        Resource rv = model.createResource(exNS + "rv_toothlength_001");
        Property hasDistribution = model.createProperty(uqNS + "hasDistribution");
        
        Literal gmmLit = rv.getProperty(hasDistribution).getLiteral();
        GMMValue gmm = (GMMValue) gmmLit.getValue();
        
        assertEquals(1, gmm.getK());
        
        double[][] means = gmm.getMeans();
        assertEquals(9.2, means[0][0], 1e-9);
        
        double[][][] covariances = gmm.getCovariances();
        assertEquals(0.04, covariances[0][0][0], 1e-9);
    }
    
    @Test
    void testSingleGaussian() {
        String dataPath = Paths.get(DATA_FILE).toAbsolutePath().toString();
        Model model = RDFDataMgr.loadModel(dataPath);
        
        String exNS = "http://example.org/data/";
        String uqNS = "http://example.org/ontology/uncertainty#";
        
        Resource rv = model.createResource(exNS + "rv_toothlength_002");
        Property hasDistribution = model.createProperty(uqNS + "hasDistribution");
        
        Literal gmmLit = rv.getProperty(hasDistribution).getLiteral();
        GMMValue gmm = (GMMValue) gmmLit.getValue();
        
        assertEquals(1, gmm.getK(), "Should be single Gaussian");
        assertArrayEquals(new double[]{1.0}, gmm.getWeights(), 1e-9);
        assertEquals(9.6, gmm.getMeans()[0][0], 1e-9);
        assertEquals(0.09, gmm.getCovariances()[0][0][0], 1e-9);
    }
    
    @Test
    void testTrimodalDistribution() {
        String dataPath = Paths.get(DATA_FILE).toAbsolutePath().toString();
        Model model = RDFDataMgr.loadModel(dataPath);
        
        String exNS = "http://example.org/data/";
        String uqNS = "http://example.org/ontology/uncertainty#";
        
        Resource rv = model.createResource(exNS + "rv_toothlength_003");
        Property hasDistribution = model.createProperty(uqNS + "hasDistribution");
        
        Literal gmmLit = rv.getProperty(hasDistribution).getLiteral();
        GMMValue gmm = (GMMValue) gmmLit.getValue();
        
        assertEquals(1, gmm.getK(), "Should have 1 component");
        assertArrayEquals(new double[]{1.0}, gmm.getWeights(), 1e-9);
        
        double[][] means = gmm.getMeans();
        assertEquals(10.5, means[0][0], 1e-9);
    }
}
