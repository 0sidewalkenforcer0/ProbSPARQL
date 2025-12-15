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
 * Example demonstrating U2: Probabilistic Comparison and Matching query.
 * 
 * Use case: Measurement engineers detect abnormal measurements by comparing results from different sensors.
 * Query: "Find all gears where the Jensen-Shannon divergence between the CT and 
 *         structured-light tooth-length distributions exceeds 0.2."
 */
public class U2_ProbabilisticComparison {
    
    private static final Logger logger = LoggerFactory.getLogger(U2_ProbabilisticComparison.class);
    
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
            logger.info("U2: PROBABILISTIC COMPARISON QUERY");
            logger.info("=".repeat(80));
            logger.info("Use Case: Abnormal measurement detection");
            logger.info("Query: \"Find all gears where the Jensen-Shannon divergence between");
            logger.info("       the CT and structured-light tooth-length distributions exceeds 0.2.\"");
            logger.info("=".repeat(80) + "\n");
            
            String queryString = new String(Files.readAllBytes(
                Paths.get("examples/queries/probabilistic_comparison.sparql")));
            
            Query query = QueryFactory.create(queryString);
            
            try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
                ResultSet results = qexec.execSelect();
                
                logger.info("RESULTS:");
                logger.info("-".repeat(120));
                logger.info(String.format("%-25s %-15s %-15s %-20s %-15s", 
                    "Gear", "CT Mean (mm)", "SL Mean (mm)", "JS Divergence", "Status"));
                logger.info("-".repeat(120));
                
                int count = 0;
                while (results.hasNext()) {
                    QuerySolution solution = results.next();
                    String gearLabel = solution.getLiteral("gearLabel").getString();
                    double ctMean = solution.getLiteral("ct_mean").getDouble();
                    double slMean = solution.getLiteral("sl_mean").getDouble();
                    double jsDivergence = solution.getLiteral("jsDivergence").getDouble();
                    String status = solution.getLiteral("status").getString();
                    
                    logger.info(String.format("%-25s %-15.2f %-15.2f %-20.4f %-15s", 
                        gearLabel, ctMean, slMean, jsDivergence, status));
                    count++;
                }
                
                logger.info("-".repeat(120));
                logger.info("Total abnormal measurements detected: {}", count);
                logger.info("\nInterpretation:");
                logger.info("  - JS divergence > 0.2 indicates significant disagreement between sensors");
                logger.info("  - {} measurement(s) flagged for quality assurance review", count);
                logger.info("  - Possible causes: calibration drift, systematic bias, or environmental factors");
                logger.info("  - Recommendation: Re-measure flagged gears and inspect sensor calibration");
                
            }
            
        } catch (Exception e) {
            logger.error("Error executing query", e);
        }
    }
}
