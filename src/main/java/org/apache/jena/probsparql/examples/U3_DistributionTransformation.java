package org.apache.jena.probsparql.examples;

import org.apache.jena.probsparql.ProbSPARQL;
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
 * Example demonstrating U3: Distribution Transformation and Propagation query.
 * 
 * Use case: Functional testers infer motor performance metrics with uncertainty propagation.
 * Query: "Retrieve all motors together with their speed, torque, and the power."
 * 
 * Power is not directly measurable but computed from uncertain speed and torque measurements:
 * P = ω × τ, where ω is angular velocity (rad/s) and τ is torque (Nm).
 * 
 * Uncertainty propagation uses first-order approximation:
 * σ²_P ≈ τ² × σ²_ω + ω² × σ²_τ (for independent variables)
 */
public class U3_DistributionTransformation {
    
    private static final Logger logger = LoggerFactory.getLogger(U3_DistributionTransformation.class);
    
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
            logger.info("\n" + "=".repeat(80));
            logger.info("U3: DISTRIBUTION TRANSFORMATION AND PROPAGATION QUERY");
            logger.info("=".repeat(80));
            logger.info("Use Case: Motor performance inference");
            logger.info("Query: \"Retrieve all motors together with their speed, torque, and the power.\"");
            logger.info("Formula: P (watts) = ω (rad/s) × τ (Nm)");
            logger.info("=".repeat(80) + "\n");
            
            String queryString = new String(Files.readAllBytes(
                Paths.get("examples/queries/distribution_transformation.sparql")));
            
            Query query = QueryFactory.create(queryString);
            
            try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
                ResultSet results = qexec.execSelect();
                
                logger.info("RESULTS:");
                logger.info("-".repeat(130));
                logger.info(String.format("%-35s %-18s %-18s %-25s %-15s", 
                    "Motor", "Speed (rad/s)", "Torque (Nm)", "Power (W)", "Status"));
                logger.info("-".repeat(130));
                
                int count = 0;
                double totalPower = 0;
                while (results.hasNext()) {
                    QuerySolution solution = results.next();
                    String motorLabel = solution.getLiteral("motorLabel").getString();
                    double speed = solution.getLiteral("speedRadPerSec").getDouble();
                    double torque = solution.getLiteral("torqueNm").getDouble();
                    
                    // Get power distribution from prob:multiply()
                    Object powerDistValue = solution.get("powerDist").asNode().getLiteralValue();
                    if (!(powerDistValue instanceof org.apache.jena.probsparql.datatypes.GMMValue)) {
                        logger.error("powerDist is not a GMMValue!");
                        continue;
                    }
                    
                    org.apache.jena.probsparql.datatypes.GMMValue powerGMM = 
                        (org.apache.jena.probsparql.datatypes.GMMValue) powerDistValue;
                    
                    // Extract mean and std from power distribution
                    double powerMean = powerGMM.getMeans()[0][0];
                    double powerVariance = powerGMM.getCovariances()[0][0][0];
                    double powerStd = Math.sqrt(powerVariance);
                    
                    String status = powerStd > 50 ? "⚠️ HIGH VARIANCE" : "✓ NORMAL";
                    
                    logger.info(String.format("%-35s %-18.2f %-18.2f %-25s %-15s", 
                        motorLabel, speed, torque, 
                        String.format("%.2f ± %.2f", powerMean, powerStd), 
                        status));
                    
                    count++;
                    totalPower += powerMean;
                }
                
                logger.info("-".repeat(130));
                logger.info("Total motors tested: {}", count);
                if (count > 0) {
                    logger.info(String.format("Average power output: %.2f W", totalPower / count));
                }
                logger.info("\nInterpretation:");
                logger.info("  - Power computed via distribution transformation: P = ω × τ");
                logger.info("  - Uncertainty propagated using prob:multiply() function");
                logger.info("  - Formula: Var[XY] ≈ μ²_X Var[Y] + μ²_Y Var[X] + Var[X]Var[Y]");
                logger.info("  - Motors with σ_P > 50W flagged as HIGH VARIANCE");
                logger.info("  - High variance may indicate sensor noise or mechanical instability");
                logger.info("  - Recommendation: Review high-variance motors for encoder/load cell calibration");
                
            }
            
        } catch (Exception e) {
            logger.error("Error executing query", e);
        }
    }
}
