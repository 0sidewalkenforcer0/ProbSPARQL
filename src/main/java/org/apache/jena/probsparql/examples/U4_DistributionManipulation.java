package org.apache.jena.probsparql.examples;

import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.util.FileManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Example demonstrating U4: Distribution Manipulation query.
 * 
 * Use case: Assembly robots require a single actionable diameter value for 
 * configuring spindle press-fit depth. Since different sensors provide distributions
 * (with multi-modal characteristics), these distributions are fused using Bayesian
 * fusion, and the MAP (Maximum A Posteriori) of the fused GMM is used as the 
 * effective diameter for robot control.
 * 
 * This example demonstrates:
 * - Multi-modal GMM fusion (K=2 caliper × K=3 laser → K=6 fused)
 * - Difference between MAP and Mean in multi-modal distributions
 * - Uncertainty reduction through sensor fusion
 * 
 * Query: "Retrieve all spindles and the fused MAP diameter."
 */
public class U4_DistributionManipulation {
    
    private static final Logger logger = LoggerFactory.getLogger(U4_DistributionManipulation.class);
    
    public static void main(String[] args) {
        // Initialize ProbSPARQL
        ProbSPARQL.init();
        
        // Load the data
        Model model = ModelFactory.createDefaultModel();
        
        logger.info("Loading angle grinder instance data...");
        InputStream dataStream = FileManager.get().open("examples/data/angle-grinder-instances.ttl");
        if (dataStream == null) {
            logger.error("Could not find data file!");
            return;
        }
        model.read(dataStream, null, "TTL");
        logger.info("Loaded {} triples", model.size());
        
        // Load and execute the query
        try {
            logger.info("\n" + "=".repeat(120));
            logger.info("U4: DISTRIBUTION MANIPULATION QUERY");
            logger.info("=".repeat(120));
            logger.info("Use Case: Assembly robot spindle press-fit configuration");
            logger.info("Query: \"Retrieve all spindles and the fused MAP diameter.\"");
            logger.info("Goal: Fuse multi-modal sensor measurements to get single actionable diameter");
            logger.info("=".repeat(120) + "\n");
            
            String queryString = new String(Files.readAllBytes(
                Paths.get("examples/queries/distribution_manipulation.sparql")));
            
            Query query = QueryFactory.create(queryString);
            
            try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
                ResultSet results = qexec.execSelect();
                
                logger.info("RESULTS:");
                logger.info("-".repeat(145));
                logger.info(String.format("%-30s %-15s %-15s %-15s %-20s %-15s %-15s", 
                    "Spindle", "Caliper (mm)", "Laser (mm)", "MAP (mm)", "Mean (mm)", "Δ(MAP-Mean)", "σ_fused (mm)"));
                logger.info("-".repeat(145));
                
                int count = 0;
                double totalMapMeanDiff = 0;
                
                while (results.hasNext()) {
                    QuerySolution solution = results.next();
                    String spindleLabel = solution.getLiteral("spindleLabel").getString();
                    double caliperValue = solution.getLiteral("caliperValue").getDouble();
                    double laserValue = solution.getLiteral("laserValue").getDouble();
                    
                    double mapDiameter = solution.getLiteral("mapDiameter").getDouble();
                    double meanDiameter = solution.getLiteral("meanDiameter").getDouble();
                    double stdDev = solution.getLiteral("stdDev").getDouble();
                    
                    // Calculate MAP - Mean difference (should be non-zero for multi-modal)
                    double mapMeanDiff = mapDiameter - meanDiameter;
                    totalMapMeanDiff += Math.abs(mapMeanDiff);
                    
                    // Get fused GMM to check K
                    Object fusedDistValue = solution.get("fusedDist").asNode().getLiteralValue();
                    GMMValue fusedGMM = (GMMValue) fusedDistValue;
                    int K = fusedGMM.getK();
                    
                    logger.info(String.format("%-30s %-15.3f %-15.3f %-15.4f %-20s %-15s %-15.4f", 
                        spindleLabel, 
                        caliperValue, 
                        laserValue,
                        mapDiameter,
                        String.format("%.4f (K=%d)", meanDiameter, K),
                        String.format("%+.4f", mapMeanDiff),
                        stdDev));
                    
                    count++;
                }
                
                logger.info("-".repeat(145));
                logger.info("Total spindles processed: {}", count);
                if (count > 0) {
                    logger.info(String.format("Average |MAP - Mean| difference: %.4f mm", 
                        totalMapMeanDiff / count));
                }
                
                logger.info("\nInterpretation:");
                logger.info("  - Each spindle measured by 2 sensors: Caliper (K=2 bimodal) + Laser (K=3 trimodal)");
                logger.info("  - Bayesian fusion produces K=6 component GMM");
                logger.info("  - MAP (Maximum A Posteriori) = mean of highest-weight component");
                logger.info("  - Mean = weighted average of all 6 components");
                logger.info("  - MAP ≠ Mean demonstrates multi-modal distribution characteristics");
                logger.info("  - Robot uses MAP value (most likely diameter) for press-fit depth control");
                logger.info("  - Mean value useful for statistical quality analysis");
                logger.info("  - Fusion reduces uncertainty: σ_fused < min(σ_caliper, σ_laser)");
                
                logger.info("\nPhysical Interpretation:");
                logger.info("  - Caliper bimodal: normal reading (70%) + parallax error mode (30%)");
                logger.info("  - Laser trimodal: elliptical cross-section measurements at different angles");
                logger.info("  - Fused MAP extracts most probable actual diameter from noisy multi-modal data");
                
            }
            
        } catch (Exception e) {
            logger.error("Error executing query", e);
        }
    }
}
