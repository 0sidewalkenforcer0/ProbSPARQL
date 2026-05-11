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
    private static final String UQ_NS = "http://example.org/ontology/uncertainty#";
    private static final String EX_NS = "http://example.org/data/";
    
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
        
        Property hasDistribution = model.createProperty(UQ_NS + "hasDistribution");
        Resource rv = model.createResource(EX_NS + "rv_ct_001");
        
        // Get the GMM literal
        Statement stmt = rv.getProperty(hasDistribution);
        assertNotNull(stmt, "Should have hasDistribution property");
        
        Literal gmmLiteral = stmt.getLiteral();
        assertNotNull(gmmLiteral);
        assertEquals(GMMDatatype.URI, gmmLiteral.getDatatypeURI());
        
        // Parse the GMM value
        Object value = gmmLiteral.getValue();
        assertInstanceOf(GMMValue.class, value);
        
        GMMValue gmm = (GMMValue) value;
        assertEquals(2, gmm.getNComponents(), "Should have 2 components");
        assertEquals(1, gmm.getDimensions(), "Should be 1-dimensional");
        assertEquals("full", gmm.getCovarianceType());
        
        double[] weights = gmm.getWeights();
        assertArrayEquals(new double[]{0.25, 0.75}, weights, 1e-9);
        
        System.out.println("Successfully extracted GMM: " + gmm);
    }
    
    @Test
    void testExtractAllRandomVariables() {
        String dataPath = Paths.get(DATA_FILE).toAbsolutePath().toString();
        Model model = RDFDataMgr.loadModel(dataPath);
        
        Resource RandomVariable = model.createResource(UQ_NS + "RandomVariable");
        Property hasDistribution = model.createProperty(UQ_NS + "hasDistribution");
        
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
                System.out.println("  n_components=" + gmm.getNComponents() + ", dimensions=" + gmm.getDimensions());
                System.out.println("  Covariance type: " + gmm.getCovarianceType());
                
                count++;
            }
        }
        
        assertEquals(32, count, "Should have exactly 32 random variables in the current sample data");
    }
    
    @Test
    void testBimodalDistribution() {
        String dataPath = Paths.get(DATA_FILE).toAbsolutePath().toString();
        Model model = RDFDataMgr.loadModel(dataPath);
        
        GMMValue gmm = getGMM(model, "rv_ct_001");
        
        assertEquals(2, gmm.getNComponents());
        
        double[][] means = gmm.getMeans();
        assertEquals(9.05, means[0][0], 1e-9);
        assertEquals(9.15, means[1][0], 1e-9);
        
        double[][][] covariances = gmm.getCovariances();
        assertEquals(0.09, covariances[0][0][0], 1e-9);
        assertEquals(0.04, covariances[1][0][0], 1e-9);
    }
    
    @Test
    void testStructuredLightDistribution() {
        String dataPath = Paths.get(DATA_FILE).toAbsolutePath().toString();
        Model model = RDFDataMgr.loadModel(dataPath);
        
        GMMValue gmm = getGMM(model, "rv_sl_002");
        
        assertEquals(2, gmm.getNComponents(), "Should be bimodal");
        assertArrayEquals(new double[]{0.75, 0.25}, gmm.getWeights(), 1e-9);
        assertEquals(9.6, gmm.getMeans()[0][0], 1e-9);
        assertEquals(9.75, gmm.getMeans()[1][0], 1e-9);
        assertEquals(0.06, gmm.getCovariances()[0][0][0], 1e-9);
        assertEquals(0.12, gmm.getCovariances()[1][0][0], 1e-9);
    }
    
    @Test
    void testTrimodalDistribution() {
        String dataPath = Paths.get(DATA_FILE).toAbsolutePath().toString();
        Model model = RDFDataMgr.loadModel(dataPath);
        
        GMMValue gmm = getGMM(model, "rv_ct_002");
        
        assertEquals(3, gmm.getNComponents(), "Should have 3 components");
        assertArrayEquals(new double[]{0.3, 0.6, 0.1}, gmm.getWeights(), 1e-9);
        
        double[][] means = gmm.getMeans();
        assertEquals(9.75, means[0][0], 1e-9);
        assertEquals(9.8, means[1][0], 1e-9);
        assertEquals(9.9, means[2][0], 1e-9);
    }

    private GMMValue getGMM(Model model, String localName) {
        Resource rv = model.createResource(EX_NS + localName);
        Property hasDistribution = model.createProperty(UQ_NS + "hasDistribution");

        Statement stmt = rv.getProperty(hasDistribution);
        assertNotNull(stmt, localName + " should have hasDistribution property");

        Literal gmmLit = stmt.getLiteral();
        assertEquals(GMMDatatype.URI, gmmLit.getDatatypeURI());
        return (GMMValue) gmmLit.getValue();
    }
}
