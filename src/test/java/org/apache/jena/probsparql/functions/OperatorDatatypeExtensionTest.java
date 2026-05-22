package org.apache.jena.probsparql.functions;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.probsparql.datatypes.DirichletDatatype;
import org.apache.jena.probsparql.datatypes.DirichletValue;
import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.datatypes.HistogramDatatype;
import org.apache.jena.probsparql.datatypes.HistogramValue;
import org.apache.jena.probsparql.functions.comparison.KLDivergence;
import org.apache.jena.probsparql.functions.manipulation.Quantile;
import org.apache.jena.probsparql.functions.thresholding.LogCDF;
import org.apache.jena.probsparql.functions.thresholding.LogPDF;
import org.apache.jena.probsparql.functions.thresholding.PDF;
import org.apache.jena.probsparql.functions.transformation.Convolve;
import org.apache.jena.probsparql.functions.transformation.Joint;
import org.apache.jena.probsparql.functions.transformation.LinearTransform;
import org.apache.jena.probsparql.functions.transformation.Marginal;
import org.apache.jena.probsparql.functions.transformation.Multiply;
import org.apache.jena.probsparql.functions.transformation.Scale;
import org.apache.jena.probsparql.functions.transformation.Shift;
import org.apache.jena.sparql.expr.NodeValue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperatorDatatypeExtensionTest {

    @BeforeAll
    static void setUp() {
        ProbSPARQL.init();
    }

    @Test
    void pdfLogPdfAndLogCdfDispatchToSampleableDatatypes() {
        NodeValue histogram = histogramNode(histogram1d(new double[]{0.0, 1.0, 2.0}, new double[]{0.25, 0.75}));
        NodeValue dirichlet = dirichletNode(new DirichletValue(new double[]{2.0, 3.0}));

        assertEquals(0.25, new PDF().exec(histogram, NodeValue.makeDouble(0.5)).getDouble(), 1e-12);
        assertEquals(Math.log(0.25), new LogPDF().exec(histogram, NodeValue.makeDouble(0.5)).getDouble(), 1e-12);
        assertEquals(Math.log(0.125), new LogCDF().exec(histogram, NodeValue.makeDouble(0.5)).getDouble(), 1e-12);

        double dirichletPdf = new PDF().exec(dirichlet, NodeValue.makeString("[0.4,0.6]")).getDouble();
        double dirichletLogPdf = new LogPDF().exec(dirichlet, NodeValue.makeString("[0.4,0.6]")).getDouble();
        double dirichletLogCdf = new LogCDF().exec(dirichlet, NodeValue.makeDouble(0.5)).getDouble();

        assertTrue(dirichletPdf > 0.0);
        assertEquals(Math.log(dirichletPdf), dirichletLogPdf, 1e-12);
        assertTrue(Double.isFinite(dirichletLogCdf));
    }

    @Test
    void histogramAffineTransformsReturnHistogramLiterals() {
        NodeValue histogram = histogramNode(histogram1d(new double[]{0.0, 1.0, 2.0}, new double[]{0.25, 0.75}));

        HistogramValue scaled = literalValue(new Scale().exec(histogram, NodeValue.makeDouble(2.0)));
        HistogramValue shifted = literalValue(new Shift().exec(histogram, NodeValue.makeDouble(3.0)));
        HistogramValue transformed = literalValue(new LinearTransform().exec(
            histogram, NodeValue.makeDouble(2.0), NodeValue.makeDouble(1.0)));

        assertArrayEquals(new double[]{0.0, 2.0, 4.0}, scaled.getBins(), 1e-12);
        assertArrayEquals(new double[]{3.0, 4.0, 5.0}, shifted.getBins(), 1e-12);
        assertArrayEquals(new double[]{1.0, 3.0, 5.0}, transformed.getBins(), 1e-12);
    }

    @Test
    void histogramCompositionOperatorsUseHistogramSemantics() {
        NodeValue left = histogramNode(histogram1d(new double[]{0.0, 1.0, 2.0}, new double[]{0.25, 0.75}));
        NodeValue right = histogramNode(histogram1d(new double[]{0.0, 1.0, 2.0}, new double[]{0.5, 0.5}));
        NodeValue twoDimensional = histogramNode(new HistogramValue(
            2,
            new double[][]{{0.0, 1.0, 2.0}, {10.0, 20.0, 30.0}},
            new double[]{0.1, 0.2, 0.3, 0.4}));

        HistogramValue marginal = literalValue(new Marginal().exec(twoDimensional, NodeValue.makeInteger(0)));
        HistogramValue joint = literalValue(new Joint().exec(left, right));
        HistogramValue multiplied = literalValue(new Multiply().exec(left, right));
        HistogramValue convolved = literalValue(new Convolve().exec(left, right));

        assertArrayEquals(new double[]{0.3, 0.7}, marginal.getWeights(), 1e-12);
        assertEquals(2, joint.getDimensions());
        assertArrayEquals(new double[]{0.125, 0.125, 0.375, 0.375}, joint.getWeights(), 1e-12);
        assertArrayEquals(new double[]{0.25, 0.75}, multiplied.getWeights(), 1e-12);
        assertArrayEquals(new double[]{0.125, 0.5, 0.375}, convolved.getWeights(), 1e-12);
    }

    @Test
    void marginalSupportsMultidimensionalGmmSubsets() {
        GMMValue gmm = new GMMValue(
            1,
            3,
            "full",
            new double[]{1.0},
            new double[][]{{1.0, 2.0, 3.0}},
            new double[][][]{{{
                1.0, 0.2, 0.3
            }, {
                0.2, 2.0, 0.4
            }, {
                0.3, 0.4, 3.0
            }}});

        GMMValue marginal = gmmLiteralValue(new Marginal().exec(gmmNode(gmm), NodeValue.makeString("[0,2]")));

        assertEquals(2, marginal.getDimensions());
        assertEquals("full", marginal.getCovarianceType());
        assertArrayEquals(new double[]{1.0, 3.0}, marginal.getMeans()[0], 1e-12);
        assertEquals(1.0, marginal.getCovariances()[0][0][0], 1e-12);
        assertEquals(0.3, marginal.getCovariances()[0][0][1], 1e-12);
        assertEquals(0.3, marginal.getCovariances()[0][1][0], 1e-12);
        assertEquals(3.0, marginal.getCovariances()[0][1][1], 1e-12);
    }

    @Test
    void marginalSupportsMultidimensionalHistogramSubsets() {
        NodeValue histogram = histogramNode(new HistogramValue(
            3,
            new double[][]{{0.0, 1.0, 2.0}, {10.0, 20.0, 30.0}, {100.0, 200.0, 300.0}},
            new double[]{0.05, 0.10, 0.15, 0.20, 0.05, 0.10, 0.15, 0.20}));

        HistogramValue marginal = literalValue(new Marginal().exec(histogram, NodeValue.makeString("[0,2]")));

        assertEquals(2, marginal.getDimensions());
        assertArrayEquals(new double[]{0.0, 1.0, 2.0}, marginal.getEdges()[0], 1e-12);
        assertArrayEquals(new double[]{100.0, 200.0, 300.0}, marginal.getEdges()[1], 1e-12);
        assertArrayEquals(new double[]{0.20, 0.30, 0.20, 0.30}, marginal.getWeights(), 1e-12);
    }

    @Test
    void histogramAndDirichletKlAndQuantileAreSupported() {
        NodeValue histA = histogramNode(histogram1d(new double[]{0.0, 1.0, 2.0}, new double[]{0.25, 0.75}));
        NodeValue histB = histogramNode(histogram1d(new double[]{0.0, 1.0, 2.0}, new double[]{0.5, 0.5}));
        NodeValue dirA = dirichletNode(new DirichletValue(new double[]{2.0, 3.0}));
        NodeValue dirB = dirichletNode(new DirichletValue(new double[]{3.0, 2.0}));

        double histKl = new KLDivergence().exec(histA, histB).getDouble();
        double dirichletKl = new KLDivergence().exec(dirA, dirB).getDouble();
        double histQuantile = new Quantile().exec(histA, NodeValue.makeDouble(0.625)).getDouble();
        double dirichletMedian = new Quantile().exec(dirA, NodeValue.makeDouble(0.5)).getDouble();

        assertTrue(histKl > 0.0);
        assertTrue(dirichletKl > 0.0);
        assertEquals(1.5, histQuantile, 1e-12);
        assertTrue(dirichletMedian > 0.0 && dirichletMedian < 1.0);
    }

    private static HistogramValue histogram1d(double[] edges, double[] weights) {
        return new HistogramValue(edges, weights);
    }

    private static NodeValue histogramNode(HistogramValue histogram) {
        Node node = NodeFactory.createLiteralDT(histogram.toString(), HistogramDatatype.INSTANCE);
        return NodeValue.makeNode(node);
    }

    private static NodeValue gmmNode(GMMValue gmm) {
        Node node = NodeFactory.createLiteralDT(gmm.toJSON(), GMMDatatype.INSTANCE);
        return NodeValue.makeNode(node);
    }

    private static NodeValue dirichletNode(DirichletValue dirichlet) {
        Node node = NodeFactory.createLiteralDT(dirichlet.toJSON(), DirichletDatatype.INSTANCE);
        return NodeValue.makeNode(node);
    }

    private static HistogramValue literalValue(NodeValue value) {
        return (HistogramValue) value.asNode().getLiteralValue();
    }

    private static GMMValue gmmLiteralValue(NodeValue value) {
        return (GMMValue) value.asNode().getLiteralValue();
    }

    private static void assertArrayEquals(double[] expected, double[] actual, double tolerance) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual, tolerance);
    }
}
