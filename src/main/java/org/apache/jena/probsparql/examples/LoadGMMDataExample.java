package org.apache.jena.probsparql.examples;

import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.RDFDataMgr;

/**
 * Example demonstrating how to load and query RDF data with GMM literals.
 * 
 * This example shows:
 * 1. Initializing ProbSPARQL
 * 2. Loading TTL data with custom GMM datatype
 * 3. Querying and extracting GMM values
 * 4. Accessing GMM properties (K, d, weights, means, covariances)
 * 
 * @author ProbSPARQL Team
 */
public class LoadGMMDataExample {
    
    public static void main(String[] args) {
        // Step 1: Initialize ProbSPARQL to register custom datatypes
        System.out.println("=== ProbSPARQL GMM Data Loading Example ===\n");
        ProbSPARQL.init();
        System.out.println("✓ ProbSPARQL initialized\n");
        
        // Step 2: Load RDF data containing GMM literals
        String dataFile = "examples/data/angle-grinder-instances.ttl";
        System.out.println("Loading data from: " + dataFile);
        Model model = RDFDataMgr.loadModel(dataFile);
        System.out.println("✓ Loaded " + model.size() + " triples\n");
        
        // Step 3: Query for random variables with GMM distributions
        String uqNS = "http://example.org/ontology/uncertainty#";
        String exNS = "http://example.org/data/";
        
        System.out.println("=== Random Variables with GMM Distributions ===\n");
        
        // Example 1: Bimodal distribution (2 components)
        System.out.println("1. Bimodal Distribution (rv_toothlength_001):");
        extractAndPrintGMM(model, exNS + "rv_toothlength_001", uqNS);
        
        // Example 2: Single Gaussian (1 component)
        System.out.println("\n2. Single Gaussian (rv_toothlength_002):");
        extractAndPrintGMM(model, exNS + "rv_toothlength_002", uqNS);
        
        // Example 3: Trimodal distribution (3 components)
        System.out.println("\n3. Trimodal Distribution (rv_toothlength_003):");
        extractAndPrintGMM(model, exNS + "rv_toothlength_003", uqNS);
        
        System.out.println("\n=== Summary ===");
        System.out.println("Successfully loaded and parsed " + 
                         countRandomVariables(model, uqNS) + 
                         " random variables with GMM distributions");
    }
    
    /**
     * Extract and print GMM details from a random variable.
     */
    private static void extractAndPrintGMM(Model model, String rvURI, String uqNS) {
        Resource rv = model.createResource(rvURI);
        Property hasDistribution = model.createProperty(uqNS + "hasDistribution");
        
        Statement stmt = rv.getProperty(hasDistribution);
        if (stmt == null) {
            System.out.println("  No distribution found");
            return;
        }
        
        Literal gmmLiteral = stmt.getLiteral();
        GMMValue gmm = (GMMValue) gmmLiteral.getValue();
        
        System.out.println("  K (components): " + gmm.getK());
        System.out.println("  d (dimensions): " + gmm.getD());
        System.out.println("  Covariance type: " + gmm.getCovarianceType());
        
        System.out.print("  Weights: [");
        double[] weights = gmm.getWeights();
        for (int i = 0; i < weights.length; i++) {
            if (i > 0) System.out.print(", ");
            System.out.printf("%.2f", weights[i]);
        }
        System.out.println("]");
        
        System.out.print("  Means: ");
        double[][] means = gmm.getMeans();
        printMatrix(means);
        
        if (gmm.getK() <= 3) {  // Only print covariances for small models
            System.out.println("  Covariances:");
            double[][][] covariances = gmm.getCovariances();
            for (int i = 0; i < covariances.length; i++) {
                System.out.print("    Component " + (i+1) + ": ");
                printMatrix(covariances[i]);
            }
        }
    }
    
    /**
     * Print a 2D matrix in compact form.
     */
    private static void printMatrix(double[][] matrix) {
        System.out.print("[");
        for (int i = 0; i < matrix.length; i++) {
            if (i > 0) System.out.print(", ");
            System.out.print("[");
            for (int j = 0; j < matrix[i].length; j++) {
                if (j > 0) System.out.print(", ");
                System.out.printf("%.2f", matrix[i][j]);
            }
            System.out.print("]");
        }
        System.out.println("]");
    }
    
    /**
     * Count random variables in the model.
     */
    private static int countRandomVariables(Model model, String uqNS) {
        Resource RandomVariable = model.createResource(uqNS + "RandomVariable");
        Property rdfType = model.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type");
        
        ResIterator iter = model.listSubjectsWithProperty(rdfType, RandomVariable);
        int count = 0;
        while (iter.hasNext()) {
            iter.nextResource();
            count++;
        }
        return count;
    }
}
