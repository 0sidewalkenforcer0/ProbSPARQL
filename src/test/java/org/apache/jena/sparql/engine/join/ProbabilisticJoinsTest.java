package org.apache.jena.sparql.engine.join;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;

import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.sparql.engine.join.ProbabilisticJoins.ProbJoinFunc;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the Probabilistic Joins framework.
 * 
 * <p>Tests the registry-based join strategy framework similar to
 * how Distances.java provides distance metrics for similarity joins.</p>
 * 
 * @author ProbSPARQL Team
 */
public class ProbabilisticJoinsTest {
    
    @BeforeAll
    public static void setup() {
        ProbSPARQL.init();
    }
    
    @Test
    public void testRegistryContainsAllStrategies() {
        List<String> strategies = ProbabilisticJoins.getRegisteredStrategies();
        
        assertTrue(strategies.contains(ProbabilisticJoins.EXACT_JOIN), 
            "Registry should contain exact join");
        assertTrue(strategies.contains(ProbabilisticJoins.FUZZY_JOIN), 
            "Registry should contain fuzzy join");
        assertTrue(strategies.contains(ProbabilisticJoins.FUSE_JOIN), 
            "Registry should contain fuse join");
        
        assertTrue(strategies.size() >= 3, "Registry should contain at least the built-in strategies");
    }
    
    @Test
    public void testGetJoinStrategy() {
        ProbJoinFunc exact = ProbabilisticJoins.getJoinStrategy(ProbabilisticJoins.EXACT_JOIN);
        assertNotNull(exact, "Exact join strategy should be registered");
        
        ProbJoinFunc fuzzy = ProbabilisticJoins.getJoinStrategy(ProbabilisticJoins.FUZZY_JOIN);
        assertNotNull(fuzzy, "Fuzzy join strategy should be registered");
        
        ProbJoinFunc fuse = ProbabilisticJoins.getJoinStrategy(ProbabilisticJoins.FUSE_JOIN);
        assertNotNull(fuse, "Fuse join strategy should be registered");
    }
    
    @Test
    public void testExactJoin_IdenticalDistributions() {
        // Create identical 1D Gaussian distributions
        GMMValue gmm1 = create1DGaussian(5.0, 1.0);
        GMMValue gmm2 = create1DGaussian(5.0, 1.0);
        
        ProbJoinFunc exactJoin = ProbabilisticJoins.getJoinStrategy(ProbabilisticJoins.EXACT_JOIN);
        
        // Test compatibility
        assertTrue(exactJoin.isCompatible(gmm1, gmm2, 0.0), 
            "Identical distributions should be compatible for exact join");
        
        // Test join
        GMMValue result = exactJoin.join(Arrays.asList(gmm1, gmm2), 0.0);
        assertNotNull(result, "Exact join should succeed for identical distributions");
        assertEquals(1, result.getDimensions(), "Result should have same dimensionality");
    }
    
    @Test
    public void testExactJoin_DifferentDistributions() {
        // Create different 1D Gaussian distributions
        GMMValue gmm1 = create1DGaussian(5.0, 1.0);
        GMMValue gmm2 = create1DGaussian(10.0, 1.0);  // Different mean
        
        ProbJoinFunc exactJoin = ProbabilisticJoins.getJoinStrategy(ProbabilisticJoins.EXACT_JOIN);
        
        // Test compatibility
        assertFalse(exactJoin.isCompatible(gmm1, gmm2, 0.0), 
            "Different distributions should not be compatible for exact join");
        
        // Test join
        GMMValue result = exactJoin.join(Arrays.asList(gmm1, gmm2), 0.0);
        assertNull(result, "Exact join should fail for different distributions");
    }
    
    @Test
    public void testFuzzyJoin_SimilarDistributions() {
        // Create similar 1D Gaussian distributions (slightly different means)
        GMMValue gmm1 = create1DGaussian(5.0, 1.0);
        GMMValue gmm2 = create1DGaussian(5.1, 1.0);  // Slightly different mean
        
        ProbJoinFunc fuzzyJoin = ProbabilisticJoins.getJoinStrategy(ProbabilisticJoins.FUZZY_JOIN);
        
        // Test compatibility with loose tolerance
        assertTrue(fuzzyJoin.isCompatible(gmm1, gmm2, 0.1), 
            "Similar distributions should be compatible with loose tolerance");
        
        // Test join
        GMMValue result = fuzzyJoin.join(Arrays.asList(gmm1, gmm2), 0.1);
        assertNotNull(result, "Fuzzy join should succeed for similar distributions");
    }
    
    @Test
    public void testFuseJoin_ThreeWayFusion() {
        // Create three similar 1D Gaussian distributions
        GMMValue gmm1 = create1DGaussian(5.0, 1.0);
        GMMValue gmm2 = create1DGaussian(5.05, 1.0);
        GMMValue gmm3 = create1DGaussian(4.95, 1.0);
        
        ProbJoinFunc fuseJoin = ProbabilisticJoins.getJoinStrategy(ProbabilisticJoins.FUSE_JOIN);
        
        // Test 3-way join
        GMMValue result = fuseJoin.join(Arrays.asList(gmm1, gmm2, gmm3), 0.1);
        assertNotNull(result, "3-way fuse join should succeed for compatible distributions");
        assertEquals(1, result.getDimensions(), "Result should maintain dimensionality");
        
        // The fused mean should be close to 5.0 (Bayesian fusion)
        double fusedMean = result.getMeans()[0][0];
        assertTrue(Math.abs(fusedMean - 5.0) < 0.1, 
            "Fused mean should be close to 5.0, got " + fusedMean);
    }
    
    @Test
    public void testFuseJoin_IncompatiblePair() {
        // Create three distributions where one pair is incompatible
        GMMValue gmm1 = create1DGaussian(5.0, 1.0);
        GMMValue gmm2 = create1DGaussian(5.05, 1.0);
        GMMValue gmm3 = create1DGaussian(50.0, 1.0);  // Incompatible with others
        
        ProbJoinFunc fuseJoin = ProbabilisticJoins.getJoinStrategy(ProbabilisticJoins.FUSE_JOIN);
        
        // Test 3-way join with strict tolerance
        GMMValue result = fuseJoin.join(Arrays.asList(gmm1, gmm2, gmm3), 0.01);
        assertNull(result, "Fuse join should fail when any pair is incompatible");
    }
    
    @Test
    public void testFuseJoin_DimensionalityValidation() {
        // Create distributions with different dimensionalities
        GMMValue gmm1D = create1DGaussian(5.0, 1.0);
        GMMValue gmm2D = create2DGaussian(5.0, 5.0, 1.0, 1.0);
        
        ProbJoinFunc fuseJoin = ProbabilisticJoins.getJoinStrategy(ProbabilisticJoins.FUSE_JOIN);
        
        // Test join with mismatched dimensions
        assertThrows(IllegalArgumentException.class, () -> {
            fuseJoin.join(Arrays.asList(gmm1D, gmm2D), 0.1);
        }, "Should throw exception for dimensionality mismatch");
    }
    
    @Test
    public void testInvalidTolerance() {
        GMMValue gmm = create1DGaussian(5.0, 1.0);
        
        ProbJoinFunc fuzzyJoin = ProbabilisticJoins.getJoinStrategy(ProbabilisticJoins.FUZZY_JOIN);
        
        // Test negative tolerance
        assertThrows(IllegalArgumentException.class, () -> {
            fuzzyJoin.isCompatible(gmm, gmm, -0.1);
        }, "Should throw exception for negative tolerance");
        
        // Test tolerance > 1
        assertThrows(IllegalArgumentException.class, () -> {
            fuzzyJoin.isCompatible(gmm, gmm, 1.5);
        }, "Should throw exception for tolerance > 1");
    }
    
    @Test
    public void testCustomStrategyRegistration() {
        // Register custom join strategy
        String customURI = "http://example.org/join#custom";
        
        ProbJoinFunc customJoin = new ProbJoinFunc() {
            @Override
            public boolean isCompatible(GMMValue gmm1, GMMValue gmm2, double tolerance) {
                return true;  // Always compatible
            }
            
            @Override
            public GMMValue join(List<GMMValue> gmms, double tolerance) {
                return gmms.get(0);  // Just return first
            }
            
            @Override
            public String getDescription() {
                return "Custom join for testing";
            }
        };
        
        ProbabilisticJoins.registerJoinStrategy(customURI, customJoin);
        
        // Verify registration
        assertTrue(ProbabilisticJoins.getRegisteredStrategies().contains(customURI),
            "Custom strategy should be registered");
        
        ProbJoinFunc retrieved = ProbabilisticJoins.getJoinStrategy(customURI);
        assertNotNull(retrieved, "Should retrieve custom strategy");
        assertEquals("Custom join for testing", retrieved.getDescription());
    }
    
    // Helper methods to create GMM values
    
    private GMMValue create1DGaussian(double mean, double std) {
        return new GMMValue(
            1, // K=1 (single component)
            1, // d=1 (1D)
            "full", // covariance type
            new double[]{1.0}, // weights
            new double[][]{{mean}}, // means
            new double[][][]{{{std * std}}} // covariances
        );
    }
    
    private GMMValue create2DGaussian(double mean1, double mean2, double std1, double std2) {
        double[][][] covariances = new double[1][2][2];
        covariances[0][0][0] = std1 * std1;
        covariances[0][0][1] = 0.0;
        covariances[0][1][0] = 0.0;
        covariances[0][1][1] = std2 * std2;
        
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
