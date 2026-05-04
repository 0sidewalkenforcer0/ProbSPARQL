package org.apache.jena.probsparql.functions.comparison;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.probsparql.datatypes.HistogramDatatype;
import org.apache.jena.probsparql.datatypes.HistogramValue;
import org.apache.jena.probsparql.functions.manipulation.HistogramMean;
import org.apache.jena.probsparql.functions.manipulation.Map;
import org.apache.jena.probsparql.functions.manipulation.Mean;
import org.apache.jena.probsparql.functions.manipulation.Std;
import org.apache.jena.probsparql.functions.thresholding.CDF;
import org.apache.jena.probsparql.functions.thresholding.HistogramCDF;
import org.apache.jena.sparql.expr.NodeValue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HistogramMultidimensionalFunctionsTest {

    @BeforeAll
    static void setUp() {
        ProbSPARQL.init();
    }

    @Test
    void testHistogramJsdMatchesPolyJsdOnShared2DGrid() {
        HistogramValue h1 = histogramA();
        HistogramValue h2 = histogramB();

        NodeValue left = literal(h1);
        NodeValue right = literal(h2);

        double histJsd = new HistogramJSD().exec(left, right).getDouble();
        double polyJsd = new PolyJSD().exec(left, right).getDouble();

        assertEquals(histJsd, polyJsd, 1e-12);
    }

    @Test
    void testJointCdfFunctionsUseMultidimensionalHistogramSemantics() {
        HistogramValue histogram = histogramA();
        NodeValue dist = literal(histogram);
        NodeValue point = NodeValue.makeString("[1.5, 15.0]");

        double direct = histogram.cdf(new double[]{1.5, 15.0});
        double alias = new HistogramCDF().exec(dist, point).getDouble();
        double poly = new CDF().exec(dist, point).getDouble();

        assertEquals(0.45, direct, 1e-12);
        assertEquals(direct, alias, 1e-12);
        assertEquals(direct, poly, 1e-12);
    }

    @Test
    void testMeanStdAndMapReturnVectorsForTwoDimensionalHistogram() {
        HistogramValue histogram = histogramA();
        NodeValue dist = literal(histogram);

        assertEquals("[1.200000, 11.000000]", new Mean().exec(dist).getString());
        assertEquals("[1.200000, 11.000000]", new HistogramMean().exec(dist).getString());
        assertEquals("[0.541603, 5.686241]", new Std().exec(dist).getString());
        assertEquals("[1.500000, 15.000000]", new Map().exec(dist).getString());
    }

    private HistogramValue histogramA() {
        return new HistogramValue(
            2,
            new double[][]{
                {0.0, 1.0, 2.0},
                {0.0, 10.0, 20.0}
            },
            new double[]{0.1, 0.2, 0.3, 0.4}
        );
    }

    private HistogramValue histogramB() {
        return new HistogramValue(
            2,
            new double[][]{
                {0.0, 1.0, 2.0},
                {0.0, 10.0, 20.0}
            },
            new double[]{0.25, 0.25, 0.2, 0.3}
        );
    }

    private NodeValue literal(HistogramValue histogram) {
        Node node = NodeFactory.createLiteralDT(histogram.toString(), HistogramDatatype.INSTANCE);
        return NodeValue.makeNode(node);
    }
}
