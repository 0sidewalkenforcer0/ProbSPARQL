package org.apache.jena.probsparql.functions;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.functions.comparison.KLDivergence;
import org.apache.jena.probsparql.functions.manipulation.Mix;
import org.apache.jena.probsparql.functions.thresholding.CDF;
import org.apache.jena.probsparql.functions.thresholding.LogCDF;
import org.apache.jena.probsparql.functions.thresholding.LogPDF;
import org.apache.jena.probsparql.functions.thresholding.PDF;
import org.apache.jena.probsparql.functions.transformation.Joint;
import org.apache.jena.probsparql.functions.transformation.Marginal;
import org.apache.jena.probsparql.functions.transformation.Scale;
import org.apache.jena.probsparql.functions.transformation.Shift;
import org.apache.jena.sparql.expr.NodeValue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GMMMultidimensionalFunctionsTest {

    @BeforeAll
    static void setUp() {
        ProbSPARQL.init();
    }

    @Test
    void testThresholdingFunctionsSupportTwoDimensionalDiagonalGMM() {
        NodeValue dist = literal(twoDimensionalDiagonalGMM("[0.0,0.0]", "[1.0,4.0]"));
        NodeValue point = NodeValue.makeString("[0.0, 0.0]");

        double pdf = new PDF().exec(dist, point).getDouble();
        double logpdf = new LogPDF().exec(dist, point).getDouble();
        double cdf = new CDF().exec(dist, point).getDouble();
        double logcdf = new LogCDF().exec(dist, point).getDouble();

        assertEquals(1.0 / (4.0 * Math.PI), pdf, 1e-12);
        assertEquals(Math.log(pdf), logpdf, 1e-12);
        assertEquals(0.25, cdf, 1e-6);
        assertEquals(Math.log(cdf), logcdf, 1e-6);
    }

    @Test
    void testKLDivergenceSupportsTwoDimensionalDiagonalGMM() {
        NodeValue dist = literal(twoDimensionalDiagonalGMM("[0.0,0.0]", "[1.0,4.0]"));

        double kl = new KLDivergence().exec(dist, dist).getDouble();

        assertEquals(0.0, kl, 1e-12);
    }

    @Test
    void testTransformationsPreserveTwoDimensionalDiagonalCovarianceLayout() {
        NodeValue dist = literal(twoDimensionalDiagonalGMM("[1.0,2.0]", "[1.0,4.0]"));

        GMMValue scaled = value(new Scale().exec(dist, NodeValue.makeDouble(2.0)));
        assertEquals("diag", scaled.getCovarianceType());
        assertEquals(2, scaled.getDimensions());
        assertEquals(2.0, scaled.getMeans()[0][0], 1e-12);
        assertEquals(4.0, scaled.getMeans()[0][1], 1e-12);
        assertEquals(4.0, scaled.getCovariances()[0][0][0], 1e-12);
        assertEquals(16.0, scaled.getCovariances()[0][0][1], 1e-12);

        GMMValue shifted = value(new Shift().exec(dist, NodeValue.makeDouble(3.0)));
        assertEquals(4.0, shifted.getMeans()[0][0], 1e-12);
        assertEquals(5.0, shifted.getMeans()[0][1], 1e-12);
        assertEquals(1.0, shifted.getCovariances()[0][0][0], 1e-12);
        assertEquals(4.0, shifted.getCovariances()[0][0][1], 1e-12);

        GMMValue marginal = value(new Marginal().exec(dist, NodeValue.makeInteger(1)));
        assertEquals(1, marginal.getDimensions());
        assertEquals(2.0, marginal.getMeans()[0][0], 1e-12);
        assertEquals(4.0, marginal.getCovariances()[0][0][0], 1e-12);
    }

    @Test
    void testFullCovarianceConversionsReadDiagonalVectorLayout() {
        NodeValue left = literal(twoDimensionalDiagonalGMM("[0.0,0.0]", "[1.0,4.0]"));
        NodeValue right = literal(twoDimensionalDiagonalGMM("[10.0,10.0]", "[9.0,16.0]"));

        GMMValue mixed = value(new Mix().exec(left, right, NodeValue.makeDouble(0.5)));
        assertEquals("full", mixed.getCovarianceType());
        assertEquals(1.0, mixed.getCovariances()[0][0][0], 1e-12);
        assertEquals(4.0, mixed.getCovariances()[0][1][1], 1e-12);
        assertEquals(9.0, mixed.getCovariances()[1][0][0], 1e-12);
        assertEquals(16.0, mixed.getCovariances()[1][1][1], 1e-12);

        GMMValue joint = value(new Joint().exec(left, right));
        assertEquals("full", joint.getCovarianceType());
        assertEquals(4, joint.getDimensions());
        assertEquals(1.0, joint.getCovariances()[0][0][0], 1e-12);
        assertEquals(4.0, joint.getCovariances()[0][1][1], 1e-12);
        assertEquals(9.0, joint.getCovariances()[0][2][2], 1e-12);
        assertEquals(16.0, joint.getCovariances()[0][3][3], 1e-12);
    }

    private GMMValue twoDimensionalDiagonalGMM(String mean, String covariance) {
        String json = "{\"n_components\":1,\"dimensions\":2,\"covariance_type\":\"diag\","
            + "\"weights\":[1.0],\"means\":[" + mean + "],\"covariances\":[" + covariance + "]}";
        return (GMMValue) GMMDatatype.INSTANCE.parse(json);
    }

    private NodeValue literal(GMMValue gmm) {
        Node node = NodeFactory.createLiteralDT(gmm.toJSON(), GMMDatatype.INSTANCE);
        return NodeValue.makeNode(node);
    }

    private GMMValue value(NodeValue node) {
        return (GMMValue) node.asNode().getLiteralValue();
    }
}
