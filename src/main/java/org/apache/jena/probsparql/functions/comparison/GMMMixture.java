package org.apache.jena.probsparql.functions.comparison;

import org.apache.jena.probsparql.datatypes.GMMValue;

/**
 * Construction of the equal-weight mixture M = ½P + ½Q used by every
 * Jensen-Shannon estimator in this package.
 *
 * <p>JSD(P‖Q) = ½KL(P‖M) + ½KL(Q‖M), so each estimator needs M materialised as a
 * GMM whose components are those of P and Q with weights halved.</p>
 *
 * <p>The subtlety this class exists to contain is the covariance representation.
 * A {@link GMMValue} stores covariances in one of three layouts selected by its
 * {@code covariance_type}: {@code full} (d×d per component), {@code diag} (a
 * single length-d row) or {@code spherical} (a single scalar). Two operands of a
 * comparison may legitimately use different layouts. Building the mixture under
 * one operand's layout therefore reinterprets the other operand's numbers — for
 * example reading a {@code full} matrix as {@code spherical} silently keeps only
 * {@code Sigma[0][0]} and discards the rest. That produces a wrong divergence in
 * one argument order and a constructor failure in the other, i.e. an asymmetric
 * JSD. When the layouts disagree this class promotes both operands to
 * {@code full}, the only layout able to represent either input exactly.</p>
 */
public final class GMMMixture {

    private GMMMixture() {
    }

    /**
     * Build M = 0.5*P + 0.5*Q.
     *
     * @param p first operand
     * @param q second operand, same dimensionality as {@code p}
     * @return a GMM with {@code p.K + q.K} components representing the even mixture
     * @throws IllegalArgumentException if the operands differ in dimensionality
     */
    public static GMMValue equalWeight(GMMValue p, GMMValue q) {
        int d = p.getDimensions();
        if (d != q.getDimensions()) {
            throw new IllegalArgumentException(
                "Mixture operands must have the same dimensionality. Got d1=" + d
                    + ", d2=" + q.getDimensions());
        }

        int kP = p.getNComponents();
        int kQ = q.getNComponents();
        int kM = kP + kQ;

        String covTypeP = p.getCovarianceType();
        String covTypeQ = q.getCovarianceType();
        // Preserve a shared layout; otherwise promote to the only lossless common one.
        String covTypeM = covTypeP.equals(covTypeQ) ? covTypeP : "full";

        double[] weightsP = p.getWeights();
        double[] weightsQ = q.getWeights();
        double[][] meansP = p.getMeans();
        double[][] meansQ = q.getMeans();
        double[][][] covsP = p.getCovariances();
        double[][][] covsQ = q.getCovariances();

        double[] weightsM = new double[kM];
        double[][] meansM = new double[kM][];
        double[][][] covsM = new double[kM][][];

        for (int k = 0; k < kP; k++) {
            weightsM[k] = 0.5 * weightsP[k];
            meansM[k] = meansP[k].clone();
            covsM[k] = convert(covsP[k], covTypeP, covTypeM, d);
        }
        for (int k = 0; k < kQ; k++) {
            weightsM[kP + k] = 0.5 * weightsQ[k];
            meansM[kP + k] = meansQ[k].clone();
            covsM[kP + k] = convert(covsQ[k], covTypeQ, covTypeM, d);
        }

        return new GMMValue(kM, d, covTypeM, weightsM, meansM, covsM);
    }

    /**
     * Copy one component's covariance from {@code sourceType} layout into
     * {@code targetType} layout. Only same-layout copies and promotion to
     * {@code full} are representable without loss.
     */
    private static double[][] convert(double[][] cov, String sourceType, String targetType, int d) {
        if (sourceType.equals(targetType)) {
            return copySameLayout(cov, sourceType);
        }
        if (!"full".equals(targetType)) {
            throw new IllegalStateException(
                "Cannot convert covariance layout " + sourceType + " to " + targetType);
        }
        return toFull(cov, sourceType, d);
    }

    private static double[][] copySameLayout(double[][] cov, String covType) {
        return switch (covType) {
            case "full" -> {
                double[][] copy = new double[cov.length][];
                for (int i = 0; i < cov.length; i++) {
                    copy[i] = cov[i].clone();
                }
                yield copy;
            }
            case "diag" -> new double[][] {cov[0].clone()};
            case "spherical" -> new double[][] {{cov[0][0]}};
            default -> throw new IllegalStateException("Unknown covariance type: " + covType);
        };
    }

    /**
     * Expand a compact covariance layout into an explicit d×d matrix.
     */
    public static double[][] toFull(double[][] cov, String sourceType, int d) {
        double[][] full = new double[d][d];
        switch (sourceType) {
            case "full" -> {
                for (int i = 0; i < d; i++) {
                    System.arraycopy(cov[i], 0, full[i], 0, d);
                }
            }
            case "diag" -> {
                for (int i = 0; i < d; i++) {
                    full[i][i] = cov[0][i];
                }
            }
            case "spherical" -> {
                for (int i = 0; i < d; i++) {
                    full[i][i] = cov[0][0];
                }
            }
            default -> throw new IllegalStateException("Unknown covariance type: " + sourceType);
        }
        return full;
    }
}
