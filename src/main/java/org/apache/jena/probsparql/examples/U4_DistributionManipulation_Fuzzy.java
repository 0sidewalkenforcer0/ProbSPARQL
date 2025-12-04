package org.apache.jena.probsparql.examples;

import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * U4: Distribution Manipulation (Fuzzy Join Version)
 * 
 * Use Case: Assembly robot spindle press-fit configuration
 * 
 * This example demonstrates:
 * 1. Using fuzzyJoin to match compatible sensor measurements
 * 2. Automatic Bayesian fusion when sensors agree (JS < tolerance)
 * 3. MAP extraction for robot control
 * 4. Detecting sensor conflicts through join filtering
 * 
 * Comparison with standard version:
 * - Standard: Fuses all caliper-laser pairs unconditionally
 * - Fuzzy: Only fuses compatible pairs (JS divergence < 0.3)
 * 
 * Advantages of fuzzyJoin approach:
 * - Automatically rejects conflicting measurements
 * - Scalable to multi-sensor scenarios (N×M pairs)
 * - Built-in quality control via tolerance threshold
 */
public class U4_DistributionManipulation_Fuzzy {
    
    private static final Logger logger = LoggerFactory.getLogger(U4_DistributionManipulation_Fuzzy.class);
    
    public static void main(String[] args) {
        // Initialize ProbSPARQL system
        ProbSPARQL.init();
        
        // Load ontology and instance data
        Model model = ModelFactory.createDefaultModel();
        model.read("examples/ontologies/angle-grinder-ontology.ttl", "TURTLE");
        model.read("examples/data/angle-grinder-instances.ttl", "TURTLE");
        
        logger.info("Loaded {} triples from ontology and instances", model.size());
        
        // Load fuzzy join version of U4 query
        String queryFile = "examples/queries/U4_distribution_manipulation_fuzzy.sparql";
        Query query = QueryFactory.read(queryFile);
        
        System.out.println("╔══════════════════════════════════════════════════════════════════════╗");
        System.out.println("║  U4: Distribution Manipulation (Fuzzy Join Version)                 ║");
        System.out.println("║  Spindle Diameter Fusion with Compatibility Check                   ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════╝");
        System.out.println();
        
        // Execute query
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            
            if (!results.hasNext()) {
                System.out.println("⚠️  No compatible sensor pairs found!");
                System.out.println("    This means all caliper-laser pairs have JS divergence > 0.3");
                System.out.println("    → Potential sensor malfunction or measurement conflict");
                return;
            }
            
            System.out.println("Compatible Sensor Pairs (JS divergence < 0.3):");
            System.out.println("═══════════════════════════════════════════════════════════════════════");
            System.out.println();
            
            int count = 0;
            while (results.hasNext()) {
                QuerySolution soln = results.nextSolution();
                count++;
                
                // Extract spindle information
                String spindleLabel = soln.getLiteral("spindleLabel").getString();
                String caliperLabel = soln.getLiteral("caliperLabel").getString();
                String laserLabel = soln.getLiteral("laserLabel").getString();
                
                // Extract fused distribution
                Literal fusedLiteral = soln.getLiteral("fusedDist");
                GMMValue fusedGMM = (GMMValue) fusedLiteral.getValue();
                
                // Extract MAP, Mean, StdDev
                double mapDiameter = soln.getLiteral("mapDiameter").getDouble();
                double meanDiameter = soln.getLiteral("meanDiameter").getDouble();
                double stdDev = soln.getLiteral("stdDev").getDouble();
                
                // Display results
                System.out.printf("Spindle: %s%n", spindleLabel);
                System.out.printf("├─ Sensors: %s + %s%n", caliperLabel, laserLabel);
                System.out.printf("├─ Compatibility: ✓ Passed fuzzyJoin (JS < 0.3)%n");
                System.out.printf("├─ Fused Distribution: K=%d components%n", fusedGMM.getK());
                System.out.printf("├─ MAP Diameter:  %.4f mm (for robot control)%n", mapDiameter);
                System.out.printf("├─ Mean Diameter: %.4f mm (for statistics)%n", meanDiameter);
                System.out.printf("├─ Std Deviation: %.4f mm (uncertainty)%n", stdDev);
                System.out.printf("└─ Δ(MAP-Mean):   %.4f mm%n", Math.abs(mapDiameter - meanDiameter));
                System.out.println();
                
                // Assess quality based on uncertainty
                if (stdDev < 0.01) {
                    System.out.println("   ✅ Excellent agreement (σ < 0.01 mm)");
                } else if (stdDev < 0.02) {
                    System.out.println("   ✓  Good agreement (0.01 ≤ σ < 0.02 mm)");
                } else {
                    System.out.println("   ⚠  Moderate agreement (σ ≥ 0.02 mm)");
                }
                
                if (stdDev < 0.01) {
                    System.out.println("   ✅ Low uncertainty - safe for high-precision assembly");
                } else if (stdDev < 0.02) {
                    System.out.println("   ✓  Medium uncertainty - acceptable for standard assembly");
                } else {
                    System.out.println("   ⚠  High uncertainty - recommend additional measurements");
                }
                
                System.out.println();
                System.out.println("───────────────────────────────────────────────────────────────────────");
                System.out.println();
            }
            
            System.out.println("═══════════════════════════════════════════════════════════════════════");
            System.out.printf("Total compatible pairs found: %d%n", count);
            System.out.println();
            
            System.out.println("Key Differences from Standard Fusion:");
            System.out.println("• Standard: Fuses all sensor pairs unconditionally");
            System.out.println("• Fuzzy:    Only fuses compatible pairs (JS < 0.3)");
            System.out.println("• Benefit:  Automatic conflict detection and filtering");
            System.out.println("• Use Case: Multi-sensor scenarios with potential faults");
            
        } catch (Exception e) {
            logger.error("Query execution failed", e);
            e.printStackTrace();
        }
    }
}
