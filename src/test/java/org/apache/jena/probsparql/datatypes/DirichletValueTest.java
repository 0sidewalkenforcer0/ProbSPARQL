package org.apache.jena.probsparql.datatypes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirichletValueTest {

    @Test
    void marginalCdfMatchesUniformBetaCase() {
        DirichletValue dirichlet = new DirichletValue(new double[]{1.0, 1.0});

        assertEquals(0.25, dirichlet.marginalCdf(0.25, 0), 1e-7);
        assertEquals(0.50, dirichlet.marginalCdf(0.50, 0), 1e-7);
        assertEquals(0.75, dirichlet.marginalCdf(0.75, 0), 1e-7);
    }

    @Test
    void marginalCdfMatchesSymmetricBetaTwoTwoCase() {
        DirichletValue dirichlet = new DirichletValue(new double[]{2.0, 2.0});

        assertEquals(0.15625, dirichlet.marginalCdf(0.25, 0), 1e-7);
        assertEquals(0.50, dirichlet.marginalCdf(0.50, 0), 1e-7);
        assertEquals(0.84375, dirichlet.marginalCdf(0.75, 0), 1e-7);
    }

    @Test
    void marginalCdfStaysInProbabilityRangeForBenchmarkShape() {
        DirichletValue dirichlet = new DirichletValue(
            new double[]{3.090519, 3.732557, 2.227903, 2.298438});

        double cdf = dirichlet.marginalCdf(0.4, 0);

        assertTrue(cdf >= 0.0 && cdf <= 1.0, "CDF must be in [0, 1], got " + cdf);
    }
}
