package org.apache.jena.probsparql.examples;

import java.util.Arrays;
import java.util.List;

import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.sparql.engine.join.ProbabilisticJoins;
import org.apache.jena.sparql.engine.join.ProbabilisticJoins.ProbJoinFunc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demonstration of the Probabilistic Join Framework.
 * 
 * <p>This example shows how to use the registry-based join framework
 * (similar to Distances.java for similarity joins) to perform N-way
 * probabilistic fusion with different join strategies.</p>
 * 
 * <p><b>Use Case:</b> Multi-sensor fusion for quality control</p>
 * <p>A manufacturing line has 4 sensors measuring the same dimension
 * of a component. We want to fuse compatible measurements to reduce
 * uncertainty while detecting incompatible (faulty) sensors.</p>
 * 
 * @author ProbSPARQL Team
 */
public class ProbabilisticJoinFrameworkDemo {
    
    private static final Logger logger = LoggerFactory.getLogger(ProbabilisticJoinFrameworkDemo.class);
    
    public static void main(String[] args) {
        // Initialize ProbSPARQL
        ProbSPARQL.init();
        
        System.out.println("╔══════════════════════════════════════════════════════════════════════╗");
        System.out.println("║  Probabilistic Join Framework Demo                                  ║");
        System.out.println("║  N-way Multi-Sensor Fusion with Compatibility Checking              ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════╝");
        System.out.println();
        
        // Demo 1: List all available join strategies
        demonstrateJoinStrategies();
        
        System.out.println("\n" + "=".repeat(80) + "\n");
        
        // Demo 2: 4-way sensor fusion (all compatible)
        demonstrateFourWayFusion();
        
        System.out.println("\n" + "=".repeat(80) + "\n");
        
        // Demo 3: Fault detection (one sensor incompatible)
        demonstrateFaultDetection();
        
        System.out.println("\n" + "=".repeat(80) + "\n");
        
        // Demo 4: Multi-dimensional fusion (2D measurements)
        demonstrateMultiDimensionalFusion();
    }
    
    /**
     * Demonstrate listing and describing all registered join strategies.
     */
    private static void demonstrateJoinStrategies() {
        System.out.println("📋 REGISTERED JOIN STRATEGIES");
        System.out.println("─".repeat(80));
        
        List<String> strategies = ProbabilisticJoins.getRegisteredStrategies();
        System.out.println("Total strategies: " + strategies.size());
        System.out.println();
        
        for (String strategyURI : strategies) {
            ProbJoinFunc func = ProbabilisticJoins.getJoinStrategy(strategyURI);
            System.out.println("Strategy: " + strategyURI);
            System.out.println("  " + func.getDescription());
            System.out.println();
        }
    }
    
    /**
     * Demonstrate 4-way sensor fusion with all sensors compatible.
     */
    private static void demonstrateFourWayFusion() {
        System.out.println("🔧 DEMO 1: Four-Way Sensor Fusion (All Compatible)");
        System.out.println("─".repeat(80));
        System.out.println("Scenario: 4 sensors measuring component diameter (mm)");
        System.out.println("All sensors agree within tolerance → Successful fusion");
        System.out.println();
        
        // Create 4 sensor measurements (slightly different but compatible)
        GMMValue sensor1 = createGaussian(12.00, 0.10);  // μ=12.00, σ=0.10
        GMMValue sensor2 = createGaussian(12.03, 0.12);  // μ=12.03, σ=0.12
        GMMValue sensor3 = createGaussian(11.98, 0.11);  // μ=11.98, σ=0.11
        GMMValue sensor4 = createGaussian(12.01, 0.09);  // μ=12.01, σ=0.09
        
        System.out.println("Sensor Measurements:");
        System.out.println("  Sensor 1: μ = 12.00 mm, σ = 0.10 mm");
        System.out.println("  Sensor 2: μ = 12.03 mm, σ = 0.12 mm");
        System.out.println("  Sensor 3: μ = 11.98 mm, σ = 0.11 mm");
        System.out.println("  Sensor 4: μ = 12.01 mm, σ = 0.09 mm");
        System.out.println();
        
        // Perform 4-way fusion using FUSE strategy
        ProbJoinFunc fuseJoin = ProbabilisticJoins.getJoinStrategy(ProbabilisticJoins.FUSE_JOIN);
        double tolerance = 0.1;  // Loose tolerance (all sensors should be compatible)
        
        System.out.println("Attempting 4-way fusion with tolerance = " + tolerance + "...");
        
        GMMValue fused = fuseJoin.join(Arrays.asList(sensor1, sensor2, sensor3, sensor4), tolerance);
        
        if (fused != null) {
            double fusedMean = fused.getMeans()[0][0];
            double fusedStd = Math.sqrt(fused.getCovariances()[0][0][0]);
            
            System.out.println("✓ Fusion successful!");
            System.out.println();
            System.out.println("Fused Result:");
            System.out.println(String.format("  μ = %.4f mm (weighted average of all sensors)", fusedMean));
            System.out.println(String.format("  σ = %.4f mm (reduced uncertainty!)", fusedStd));
            System.out.println();
            System.out.println("Uncertainty Reduction:");
            System.out.println(String.format("  Before: σ ≈ 0.10 mm (single sensor)"));
            System.out.println(String.format("  After:  σ = %.4f mm (%.1f%% reduction)", 
                fusedStd, (1 - fusedStd / 0.10) * 100));
        } else {
            System.out.println("✗ Fusion failed (sensors incompatible)");
        }
    }
    
    /**
     * Demonstrate fault detection when one sensor is incompatible.
     */
    private static void demonstrateFaultDetection() {
        System.out.println("⚠️  DEMO 2: Fault Detection (One Incompatible Sensor)");
        System.out.println("─".repeat(80));
        System.out.println("Scenario: 4 sensors, but Sensor 3 is faulty (different reading)");
        System.out.println("Fusion should fail → Detect sensor fault");
        System.out.println();
        
        // Create 4 sensors, but #3 is faulty (very different reading)
        GMMValue sensor1 = createGaussian(12.00, 0.10);
        GMMValue sensor2 = createGaussian(12.03, 0.12);
        GMMValue sensor3 = createGaussian(15.50, 0.11);  // FAULTY! Much higher
        GMMValue sensor4 = createGaussian(12.01, 0.09);
        
        System.out.println("Sensor Measurements:");
        System.out.println("  Sensor 1: μ = 12.00 mm, σ = 0.10 mm ✓");
        System.out.println("  Sensor 2: μ = 12.03 mm, σ = 0.12 mm ✓");
        System.out.println("  Sensor 3: μ = 15.50 mm, σ = 0.11 mm ⚠️  SUSPECT!");
        System.out.println("  Sensor 4: μ = 12.01 mm, σ = 0.09 mm ✓");
        System.out.println();
        
        // Try 4-way fusion
        ProbJoinFunc fuseJoin = ProbabilisticJoins.getJoinStrategy(ProbabilisticJoins.FUSE_JOIN);
        double tolerance = 0.1;  // Strict tolerance
        
        System.out.println("Attempting 4-way fusion with tolerance = " + tolerance + "...");
        
        GMMValue fused = fuseJoin.join(Arrays.asList(sensor1, sensor2, sensor3, sensor4), tolerance);
        
        if (fused != null) {
            System.out.println("✗ Fusion succeeded (unexpected - should have detected fault)");
        } else {
            System.out.println("✓ Fusion FAILED (as expected)");
            System.out.println();
            System.out.println("Fault Detection Analysis:");
            System.out.println("  The framework detected that Sensor 3 is incompatible with others");
            System.out.println("  Pairwise JS divergence between Sensor 3 and others exceeds tolerance");
            System.out.println();
            System.out.println("Recommended Action:");
            System.out.println("  1. Exclude Sensor 3 from fusion");
            System.out.println("  2. Investigate Sensor 3 for calibration issues");
            System.out.println("  3. Re-run fusion with remaining 3 sensors");
            
            // Try again without faulty sensor
            System.out.println();
            System.out.println("Attempting 3-way fusion (excluding Sensor 3)...");
            GMMValue fusedGood = fuseJoin.join(Arrays.asList(sensor1, sensor2, sensor4), tolerance);
            
            if (fusedGood != null) {
                double fusedMean = fusedGood.getMeans()[0][0];
                double fusedStd = Math.sqrt(fusedGood.getCovariances()[0][0][0]);
                System.out.println("✓ 3-way fusion successful!");
                System.out.println(String.format("  μ = %.4f mm, σ = %.4f mm", fusedMean, fusedStd));
            }
        }
    }
    
    /**
     * Demonstrate multi-dimensional (2D) fusion.
     */
    private static void demonstrateMultiDimensionalFusion() {
        System.out.println("📊 DEMO 3: Multi-Dimensional Fusion (2D Measurements)");
        System.out.println("─".repeat(80));
        System.out.println("Scenario: 3 sensors measuring (width, height) of a component");
        System.out.println("Type-aware fusion ensures dimensionality consistency");
        System.out.println();
        
        // Create 3 sensors measuring 2D (width, height)
        GMMValue sensor1 = create2DGaussian(10.0, 20.0, 0.1, 0.2);  // (w=10, h=20)
        GMMValue sensor2 = create2DGaussian(10.05, 20.03, 0.12, 0.18);
        GMMValue sensor3 = create2DGaussian(9.98, 19.97, 0.11, 0.21);
        
        System.out.println("Sensor Measurements:");
        System.out.println("  Sensor 1: (w, h) = (10.00, 20.00) mm");
        System.out.println("  Sensor 2: (w, h) = (10.05, 20.03) mm");
        System.out.println("  Sensor 3: (w, h) = ( 9.98, 19.97) mm");
        System.out.println();
        
        // Perform 3-way 2D fusion
        ProbJoinFunc fuseJoin = ProbabilisticJoins.getJoinStrategy(ProbabilisticJoins.FUSE_JOIN);
        double tolerance = 0.1;
        
        System.out.println("Attempting 3-way 2D fusion with tolerance = " + tolerance + "...");
        
        GMMValue fused = fuseJoin.join(Arrays.asList(sensor1, sensor2, sensor3), tolerance);
        
        if (fused != null) {
            double[] fusedMean = fused.getMeans()[0];
            double[][] fusedCov = fused.getCovariances()[0];
            
            System.out.println("✓ 2D Fusion successful!");
            System.out.println();
            System.out.println("Fused Result:");
            System.out.println(String.format("  Mean:   (w, h) = (%.4f, %.4f) mm", fusedMean[0], fusedMean[1]));
            System.out.println(String.format("  Std:    (σ_w, σ_h) = (%.4f, %.4f) mm", 
                Math.sqrt(fusedCov[0][0]), Math.sqrt(fusedCov[1][1])));
            System.out.println();
            System.out.println("Dimensionality: d = " + fused.getDimensions() + " (preserved)");
        } else {
            System.out.println("✗ Fusion failed");
        }
    }
    
    /**
     * Create a 1D Gaussian GMM.
     */
    private static GMMValue createGaussian(double mean, double std) {
        return new GMMValue(
            1, // K=1 (single component)
            1, // d=1 (1D)
            "full", // covariance type
            new double[]{1.0}, // weights
            new double[][]{{mean}}, // means
            new double[][][]{{{std * std}}} // covariances
        );
    }
    
    /**
     * Create a 2D Gaussian GMM.
     */
    private static GMMValue create2DGaussian(double mean1, double mean2, 
                                              double std1, double std2) {
        // For 2D full covariance: covariances[0] must be 2×2 matrix
        double[][][] covariances = new double[1][2][2];
        covariances[0][0][0] = std1 * std1;  // Var(X)
        covariances[0][0][1] = 0.0;          // Cov(X,Y)
        covariances[0][1][0] = 0.0;          // Cov(Y,X)
        covariances[0][1][1] = std2 * std2;  // Var(Y)
        
        return new GMMValue(
            1, // K=1
            2, // d=2 (2D)
            "full",
            new double[]{1.0},
            new double[][]{{mean1, mean2}},
            covariances
        );
    }
}
