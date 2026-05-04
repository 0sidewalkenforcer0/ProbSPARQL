package org.apache.jena.probsparql.datatypes;

import org.apache.jena.datatypes.DatatypeFormatException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistogramDatatypeTest {

    @Test
    void testLegacyOneDimensionalSchemaStillParses() {
        String legacyJson = "{\"bins\":[0.0,1.0,2.0],\"weights\":[0.4,0.6]}";

        HistogramValue histogram = (HistogramValue) HistogramDatatype.INSTANCE.parse(legacyJson);

        assertEquals(1, histogram.getDimensions());
        assertArrayEquals(new int[]{2}, histogram.getBinCounts());
        assertArrayEquals(new double[]{0.0, 1.0, 2.0}, histogram.getBins(), 1e-12);

        String canonical = HistogramDatatype.INSTANCE.unparse(histogram);
        assertTrue(canonical.contains("\"dimensions\":1"));
        assertTrue(canonical.contains("\"edges\":[[0.0,1.0,2.0]]"));
    }

    @Test
    void testTwoDimensionalSchemaParsesAndComputesJointCdf() {
        HistogramValue histogram = parse2DHistogram();

        assertEquals(2, histogram.getDimensions());
        assertArrayEquals(new int[]{2, 2}, histogram.getBinCounts());
        assertEquals(Math.log(0.1 / 10.0), histogram.logPdf(new double[]{0.5, 5.0}), 1e-12);
        assertEquals(0.45, histogram.cdf(new double[]{1.5, 15.0}), 1e-12);
    }

    @Test
    void testInvalidWeightLengthIsRejected() {
        String invalidJson = """
            {"dimensions":2,"edges":[[0.0,1.0,2.0],[0.0,10.0,20.0]],"weights":[0.1,0.2,0.7]}
            """;

        try {
            HistogramDatatype.INSTANCE.parse(invalidJson);
        } catch (DatatypeFormatException e) {
            assertTrue(e.getMessage().contains("expected cell count"));
            return;
        }
        throw new AssertionError("Expected DatatypeFormatException for mismatched weight length");
    }

    private HistogramValue parse2DHistogram() {
        String json = """
            {"dimensions":2,"edges":[[0.0,1.0,2.0],[0.0,10.0,20.0]],"weights":[0.1,0.2,0.3,0.4]}
            """;
        return (HistogramValue) HistogramDatatype.INSTANCE.parse(json);
    }
}
