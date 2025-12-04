package org.apache.jena.probsparql.functions.manipulation;

import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.sparql.expr.NodeValue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for manipulation functions.
 */
public class ManipulationFunctionsTest {
    
    @BeforeAll
    public static void setup() {
        ProbSPARQL.init();
    }
    
    @Test
    public void testMean_SingleComponent1D() {
        // Single Gaussian N(5.0, 1.0)
        GMMValue gmm = new GMMValue(
            1, 1, "full",
            new double[]{1.0},
            new double[][]{{5.0}},
            new double[][][]{{{1.0}}}
        );
        
        org.apache.jena.graph.Node node = org.apache.jena.graph.NodeFactory.createLiteralDT(
            gmm.toJSON(), GMMDatatype.INSTANCE);
        NodeValue gmmNode = NodeValue.makeNode(node);
        
        Mean meanFunc = new Mean();
        NodeValue result = meanFunc.exec(gmmNode);
        
        assertTrue(result.isString());
        String meanStr = result.getString();
        assertTrue(meanStr.contains("5.0"), "Mean should be approximately 5.0");
    }
    
    @Test
    public void testMean_MultiComponent1D() {
        // Bimodal GMM: 0.6*N(2.0, 0.5) + 0.4*N(8.0, 1.0)
        // Expected mean = 0.6*2 + 0.4*8 = 4.4
        GMMValue gmm = new GMMValue(
            2, 1, "full",
            new double[]{0.6, 0.4},
            new double[][]{{2.0}, {8.0}},
            new double[][][]{{{0.5}}, {{1.0}}}
        );
        
        org.apache.jena.graph.Node node = org.apache.jena.graph.NodeFactory.createLiteralDT(
            gmm.toJSON(), GMMDatatype.INSTANCE);
        NodeValue gmmNode = NodeValue.makeNode(node);
        
        Mean meanFunc = new Mean();
        NodeValue result = meanFunc.exec(gmmNode);
        
        assertTrue(result.isString());
        String meanStr = result.getString();
        assertTrue(meanStr.contains("4.4"), "Mean should be 4.4");
    }
    
    @Test
    public void testMean_MultiDimensional() {
        // 2D GMM: N([1.0, 2.0], I)
        GMMValue gmm = new GMMValue(
            1, 2, "full",
            new double[]{1.0},
            new double[][]{{1.0, 2.0}},
            new double[][][]{{{1.0, 0.0}, {0.0, 1.0}}}
        );
        
        org.apache.jena.graph.Node node = org.apache.jena.graph.NodeFactory.createLiteralDT(
            gmm.toJSON(), GMMDatatype.INSTANCE);
        NodeValue gmmNode = NodeValue.makeNode(node);
        
        Mean meanFunc = new Mean();
        NodeValue result = meanFunc.exec(gmmNode);
        
        assertTrue(result.isString());
        String meanStr = result.getString();
        assertTrue(meanStr.contains("1.0"));
        assertTrue(meanStr.contains("2.0"));
    }
    
    @Test
    public void testStd_SingleComponent() {
        // N(5.0, 4.0) -> std = 2.0
        GMMValue gmm = new GMMValue(
            1, 1, "full",
            new double[]{1.0},
            new double[][]{{5.0}},
            new double[][][]{{{4.0}}}
        );
        
        org.apache.jena.graph.Node node = org.apache.jena.graph.NodeFactory.createLiteralDT(
            gmm.toJSON(), GMMDatatype.INSTANCE);
        NodeValue gmmNode = NodeValue.makeNode(node);
        
        Std stdFunc = new Std();
        NodeValue result = stdFunc.exec(gmmNode);
        
        assertTrue(result.isString());
        String stdStr = result.getString();
        assertTrue(stdStr.contains("2.0"), "Std should be 2.0");
    }
    
    @Test
    public void testMap_FindsMaxWeightComponent() {
        // Bimodal: 0.3*N(2.0, 1.0) + 0.7*N(8.0, 1.0)
        // MAP should be 8.0 (component with weight 0.7)
        GMMValue gmm = new GMMValue(
            2, 1, "full",
            new double[]{0.3, 0.7},
            new double[][]{{2.0}, {8.0}},
            new double[][][]{{{1.0}}, {{1.0}}}
        );
        
        org.apache.jena.graph.Node node = org.apache.jena.graph.NodeFactory.createLiteralDT(
            gmm.toJSON(), GMMDatatype.INSTANCE);
        NodeValue gmmNode = NodeValue.makeNode(node);
        
        Map mapFunc = new Map();
        NodeValue result = mapFunc.exec(gmmNode);
        
        assertTrue(result.isString());
        String mapStr = result.getString();
        assertTrue(mapStr.contains("8.0"), "MAP should be 8.0");
    }
    
    @Test
    public void testModeCount_ReturnsK() {
        GMMValue gmm = new GMMValue(
            3, 1, "full",
            new double[]{0.3, 0.5, 0.2},
            new double[][]{{1.0}, {5.0}, {9.0}},
            new double[][][]{{{1.0}}, {{1.0}}, {{1.0}}}
        );
        
        org.apache.jena.graph.Node node = org.apache.jena.graph.NodeFactory.createLiteralDT(
            gmm.toJSON(), GMMDatatype.INSTANCE);
        NodeValue gmmNode = NodeValue.makeNode(node);
        
        ModeCount modeCountFunc = new ModeCount();
        NodeValue result = modeCountFunc.exec(gmmNode);
        
        assertTrue(result.isInteger());
        assertEquals(3, result.getInteger().intValue());
    }
    
    @Test
    public void testMix_CombinesGMMs() {
        // GMM1: N(0.0, 1.0)
        GMMValue gmm1 = new GMMValue(
            1, 1, "full",
            new double[]{1.0},
            new double[][]{{0.0}},
            new double[][][]{{{1.0}}}
        );
        
        // GMM2: N(10.0, 1.0)
        GMMValue gmm2 = new GMMValue(
            1, 1, "full",
            new double[]{1.0},
            new double[][]{{10.0}},
            new double[][][]{{{1.0}}}
        );
        
        org.apache.jena.graph.Node node1 = org.apache.jena.graph.NodeFactory.createLiteralDT(
            gmm1.toJSON(), GMMDatatype.INSTANCE);
        org.apache.jena.graph.Node node2 = org.apache.jena.graph.NodeFactory.createLiteralDT(
            gmm2.toJSON(), GMMDatatype.INSTANCE);
        
        NodeValue gmm1Node = NodeValue.makeNode(node1);
        NodeValue gmm2Node = NodeValue.makeNode(node2);
        NodeValue alphaNode = NodeValue.makeDouble(0.7);
        
        Mix mixFunc = new Mix();
        NodeValue result = mixFunc.exec(gmm1Node, gmm2Node, alphaNode);
        
        assertTrue(result.isLiteral());
        GMMValue mixed = (GMMValue) result.asNode().getLiteralValue();
        
        // Should have K=2 components
        assertEquals(2, mixed.getK());
        
        // Weights should be [0.7, 0.3]
        assertEquals(0.7, mixed.getWeights()[0], 1e-6);
        assertEquals(0.3, mixed.getWeights()[1], 1e-6);
        
        // Means should be [0.0, 10.0]
        assertEquals(0.0, mixed.getMeans()[0][0], 1e-6);
        assertEquals(10.0, mixed.getMeans()[1][0], 1e-6);
    }
    
    @Test
    public void testFuse_BayesianProduct() {
        // Prior: N(0.0, 4.0)
        GMMValue prior = new GMMValue(
            1, 1, "full",
            new double[]{1.0},
            new double[][]{{0.0}},
            new double[][][]{{{4.0}}}
        );
        
        // Likelihood: N(6.0, 4.0)
        GMMValue likelihood = new GMMValue(
            1, 1, "full",
            new double[]{1.0},
            new double[][]{{6.0}},
            new double[][][]{{{4.0}}}
        );
        
        org.apache.jena.graph.Node node1 = org.apache.jena.graph.NodeFactory.createLiteralDT(
            prior.toJSON(), GMMDatatype.INSTANCE);
        org.apache.jena.graph.Node node2 = org.apache.jena.graph.NodeFactory.createLiteralDT(
            likelihood.toJSON(), GMMDatatype.INSTANCE);
        
        NodeValue priorNode = NodeValue.makeNode(node1);
        NodeValue likelihoodNode = NodeValue.makeNode(node2);
        
        Fuse fuseFunc = new Fuse();
        NodeValue result = fuseFunc.exec(priorNode, likelihoodNode);
        
        assertTrue(result.isLiteral());
        GMMValue posterior = (GMMValue) result.asNode().getLiteralValue();
        
        // Product of two Gaussians N(0,4) and N(6,4)
        // Posterior should be N(3.0, 2.0)
        assertEquals(1, posterior.getK());
        
        // Mean should be around 3.0
        double posteriorMean = posterior.getMeans()[0][0];
        assertEquals(3.0, posteriorMean, 0.1);
        
        // Variance should be around 2.0
        double posteriorVar = posterior.getCovariances()[0][0][0];
        assertEquals(2.0, posteriorVar, 0.1);
    }
    
    @Test
    public void testQuantile_Median() {
        // N(5.0, 1.0)
        // Median (q=0.5) should be 5.0
        GMMValue gmm = new GMMValue(
            1, 1, "full",
            new double[]{1.0},
            new double[][]{{5.0}},
            new double[][][]{{{1.0}}}
        );
        
        org.apache.jena.graph.Node node = org.apache.jena.graph.NodeFactory.createLiteralDT(
            gmm.toJSON(), GMMDatatype.INSTANCE);
        NodeValue gmmNode = NodeValue.makeNode(node);
        NodeValue qNode = NodeValue.makeDouble(0.5);
        
        Quantile quantileFunc = new Quantile();
        NodeValue result = quantileFunc.exec(gmmNode, qNode);
        
        assertTrue(result.isDouble());
        double median = result.getDouble();
        assertEquals(5.0, median, 0.01);
    }
    
    @Test
    public void testQuantile_95thPercentile() {
        // N(0.0, 1.0)
        // 95th percentile should be around 1.645
        GMMValue gmm = new GMMValue(
            1, 1, "full",
            new double[]{1.0},
            new double[][]{{0.0}},
            new double[][][]{{{1.0}}}
        );
        
        org.apache.jena.graph.Node node = org.apache.jena.graph.NodeFactory.createLiteralDT(
            gmm.toJSON(), GMMDatatype.INSTANCE);
        NodeValue gmmNode = NodeValue.makeNode(node);
        NodeValue qNode = NodeValue.makeDouble(0.95);
        
        Quantile quantileFunc = new Quantile();
        NodeValue result = quantileFunc.exec(gmmNode, qNode);
        
        assertTrue(result.isDouble());
        double q95 = result.getDouble();
        assertEquals(1.645, q95, 0.05);
    }
    
    @Test
    public void testQuantile_BimodalGMM() {
        // Bimodal: 0.5*N(-2.0, 0.5) + 0.5*N(2.0, 0.5)
        GMMValue gmm = new GMMValue(
            2, 1, "full",
            new double[]{0.5, 0.5},
            new double[][]{{-2.0}, {2.0}},
            new double[][][]{{{0.5}}, {{0.5}}}
        );
        
        org.apache.jena.graph.Node node = org.apache.jena.graph.NodeFactory.createLiteralDT(
            gmm.toJSON(), GMMDatatype.INSTANCE);
        NodeValue gmmNode = NodeValue.makeNode(node);
        NodeValue qNode = NodeValue.makeDouble(0.5);
        
        Quantile quantileFunc = new Quantile();
        NodeValue result = quantileFunc.exec(gmmNode, qNode);
        
        assertTrue(result.isDouble());
        double median = result.getDouble();
        // Median should be around 0 due to symmetry
        assertEquals(0.0, median, 0.2);
    }
    
    @Test
    public void testFuse_MultiComponentGMMs() {
        // Prior: 0.5*N(0,1) + 0.5*N(4,1)
        GMMValue prior = new GMMValue(
            2, 1, "full",
            new double[]{0.5, 0.5},
            new double[][]{{0.0}, {4.0}},
            new double[][][]{{{1.0}}, {{1.0}}}
        );
        
        // Likelihood: N(2.0, 1.0)
        GMMValue likelihood = new GMMValue(
            1, 1, "full",
            new double[]{1.0},
            new double[][]{{2.0}},
            new double[][][]{{{1.0}}}
        );
        
        org.apache.jena.graph.Node node1 = org.apache.jena.graph.NodeFactory.createLiteralDT(
            prior.toJSON(), GMMDatatype.INSTANCE);
        org.apache.jena.graph.Node node2 = org.apache.jena.graph.NodeFactory.createLiteralDT(
            likelihood.toJSON(), GMMDatatype.INSTANCE);
        
        NodeValue priorNode = NodeValue.makeNode(node1);
        NodeValue likelihoodNode = NodeValue.makeNode(node2);
        
        Fuse fuseFunc = new Fuse();
        NodeValue result = fuseFunc.exec(priorNode, likelihoodNode);
        
        assertTrue(result.isLiteral());
        GMMValue posterior = (GMMValue) result.asNode().getLiteralValue();
        
        // Should have K = 2*1 = 2 components
        assertEquals(2, posterior.getK());
        
        // Weights should sum to 1
        double weightSum = 0.0;
        for (double w : posterior.getWeights()) {
            weightSum += w;
        }
        assertEquals(1.0, weightSum, 1e-6);
    }
}
