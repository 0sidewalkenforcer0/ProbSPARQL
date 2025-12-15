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
 * Example demonstrating U1: Probabilistic Thresholding query.
 * 
 * Use case: Repair technicians identify gears whose teeth are likely to be excessively worn.
 * Query: "Give me all gears whose tooth length is below 9.8mm with probability at least 0.9."
 */
public class U1_ProbabilisticThresholding {
    
    private static final Logger logger = LoggerFactory.getLogger(U1_ProbabilisticThresholding.class);
    
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
            logger.info("U1: PROBABILISTIC THRESHOLDING QUERY");
            logger.info("=".repeat(80));
            logger.info("Use Case: Damaged component retrieval");
            logger.info("Query: \"Give me all gears whose tooth length is below 9.8mm");
            logger.info("       with probability at least 0.9.\"");
            logger.info("=".repeat(80) + "\n");
            
            String queryString = new String(Files.readAllBytes(
                Paths.get("examples/queries/probabilistic_thresholding.sparql")));
            
            Query query = QueryFactory.create(queryString);
            
            try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
                ResultSet results = qexec.execSelect();
                
                logger.info("RESULTS:");
                logger.info("-".repeat(120));
                logger.info(String.format("%-25s %-20s %-15s", "Gear", "Point Estimate", "P(worn < 9.8mm)"));
                logger.info("-".repeat(120));
                
                int count = 0;
                while (results.hasNext()) {
                    QuerySolution solution = results.next();
                    String gearLabel = solution.getLiteral("gearLabel").getString();
                    double pointEstimate = solution.getLiteral("pointEstimate").getDouble();
                    double probability = solution.getLiteral("probability").getDouble();
                    
                    logger.info(String.format("%-25s %-20.2f mm %-15.4f (%.1f%%)", 
                        gearLabel, pointEstimate, probability, probability * 100));
                    count++;
                }
                
                logger.info("-".repeat(120));
                logger.info("Total worn gears detected: {}", count);
                logger.info("\nInterpretation:");
                logger.info("  - These {} gears have at least 90% probability of tooth length < 9.8mm", count);
                logger.info("  - They should be flagged for replacement or maintenance");
                logger.info("  - Probability threshold (0.9) ensures high confidence in detection");
                
            }
            
        } catch (Exception e) {
            logger.error("Error executing query", e);
        }
    }
}
