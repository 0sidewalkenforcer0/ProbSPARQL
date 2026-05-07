package org.apache.jena.probsparql;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.Syntax;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.probsparql.datatypes.DirichletDatatype;
import org.apache.jena.probsparql.datatypes.DirichletValue;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.datatypes.HistogramDatatype;
import org.apache.jena.probsparql.datatypes.HistogramValue;
import org.apache.jena.probsparql.functions.comparison.JSDivergenceConfig;
import org.apache.jena.sparql.algebra.AlgebraGeneratorProbabilistic;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.op.Op1;
import org.apache.jena.sparql.algebra.op.Op2;
import org.apache.jena.sparql.algebra.op.OpExt;
import org.apache.jena.sparql.algebra.op.OpN;
import org.apache.jena.sparql.algebra.op.OpSimilarityJoin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SimilarityJoinSyntaxTest {

    private static final String EX_NS = "http://example.org/";

    @BeforeAll
    static void setUp() {
        ProbSPARQL.init();
    }

    @Test
    void testFourArgumentSimilarityJoinParsesIntoTailAwareOperator() {
        String queryString = """
            PREFIX ex: <http://example.org/>
            SELECT * WHERE {
              { ?left ex:dist ?d1 . }
              DIVJOIN(?d1, ?d2, 0.3, 0.05)
              { ?right ex:dist ?d2 . }
            }
            """;

        Query query = QueryFactory.create(queryString, Syntax.syntaxARQ);
        Op op = new AlgebraGeneratorProbabilistic().compile(query);
        OpSimilarityJoin similarityJoin = findSimilarityJoin(op);

        assertNotNull(similarityJoin, "Compiled algebra should contain an OpSimilarityJoin");
        assertEquals(0.3, similarityJoin.getTolerance(), 1e-12);
        assertEquals(0.05, similarityJoin.getTailProbability(), 1e-12);
    }

    @Test
    void testLegacySimilarityJoinKeywordIsRejected() {
        String queryString = """
            PREFIX ex: <http://example.org/>
            SELECT * WHERE {
              { ?left ex:dist ?d1 . }
              SIMILARITYJOIN(?d1, ?d2, 0.3, 0.05)
              { ?right ex:dist ?d2 . }
            }
            """;

        assertThrows(Exception.class, () -> QueryFactory.create(queryString, Syntax.syntaxARQ));
    }

    @Test
    void testFourArgumentSimilarityJoinExecutesThroughProbabilisticEngine() {
        System.setProperty("probsparql.mode", JSDivergenceConfig.MODE_V3_SPRT);
        try {
            Model model = ModelFactory.createDefaultModel();
            Resource left = model.createResource(EX_NS + "left");
            Resource right = model.createResource(EX_NS + "right");

            GMMValue leftGmm = create1DGaussian(0.0, 1.0);
            GMMValue rightGmm = create1DGaussian(0.2, 1.0);

            left.addProperty(
                model.createProperty(EX_NS + "dist"),
                model.asRDFNode(NodeFactory.createLiteralDT(leftGmm.toJSON(), GMMDatatype.INSTANCE))
            );
            right.addProperty(
                model.createProperty(EX_NS + "dist"),
                model.asRDFNode(NodeFactory.createLiteralDT(rightGmm.toJSON(), GMMDatatype.INSTANCE))
            );

            Query query = QueryFactory.create("""
                PREFIX ex: <http://example.org/>
                SELECT * WHERE {
                  { ex:left ex:dist ?d1 . }
                  DIVJOIN(?d1, ?d2, 0.3, 0.05)
                  { ex:right ex:dist ?d2 . }
                }
                """, Syntax.syntaxARQ);

            try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
                ResultSet results = qexec.execSelect();
                assertTrue(results.hasNext(),
                    "A four-argument DIVJOIN query should execute and return the similar pair");
            }
        } finally {
            System.clearProperty("probsparql.mode");
        }
    }

    @Test
    void testHistogramSimilarityJoinExecutesThroughProbabilisticEngine() {
        Model model = ModelFactory.createDefaultModel();
        Resource left = model.createResource(EX_NS + "histLeft");
        Resource right = model.createResource(EX_NS + "histRight");

        HistogramValue leftHist = new HistogramValue(
            new double[]{0.0, 1.0, 2.0},
            new double[]{0.5, 0.5}
        );
        HistogramValue rightHist = new HistogramValue(
            new double[]{0.0, 1.0, 2.0},
            new double[]{0.55, 0.45}
        );

        left.addProperty(
            model.createProperty(EX_NS + "dist"),
            model.asRDFNode(NodeFactory.createLiteralDT(leftHist.toString(), HistogramDatatype.INSTANCE))
        );
        right.addProperty(
            model.createProperty(EX_NS + "dist"),
            model.asRDFNode(NodeFactory.createLiteralDT(rightHist.toString(), HistogramDatatype.INSTANCE))
        );

        Query query = QueryFactory.create("""
            PREFIX ex: <http://example.org/>
            SELECT * WHERE {
              { ex:histLeft ex:dist ?d1 . }
              DIVJOIN(?d1, ?d2, 0.3, 0.05)
              { ex:histRight ex:dist ?d2 . }
            }
            """, Syntax.syntaxARQ);

        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(),
                "Histogram DIVJOIN should use the polymorphic similarity path");
        }
    }

    @Test
    void testDirichletSimilarityJoinExecutesThroughProbabilisticEngine() {
        Model model = ModelFactory.createDefaultModel();
        Resource left = model.createResource(EX_NS + "dirLeft");
        Resource right = model.createResource(EX_NS + "dirRight");

        DirichletValue leftDir = new DirichletValue(new double[]{4.0, 3.0, 2.0});
        DirichletValue rightDir = new DirichletValue(new double[]{4.1, 2.9, 2.0});

        left.addProperty(
            model.createProperty(EX_NS + "dist"),
            model.asRDFNode(NodeFactory.createLiteralDT(leftDir.toJSON(), DirichletDatatype.INSTANCE))
        );
        right.addProperty(
            model.createProperty(EX_NS + "dist"),
            model.asRDFNode(NodeFactory.createLiteralDT(rightDir.toJSON(), DirichletDatatype.INSTANCE))
        );

        Query query = QueryFactory.create("""
            PREFIX ex: <http://example.org/>
            SELECT * WHERE {
              { ex:dirLeft ex:dist ?d1 . }
              DIVJOIN(?d1, ?d2, 0.3, 0.05)
              { ex:dirRight ex:dist ?d2 . }
            }
            """, Syntax.syntaxARQ);

        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(),
                "Dirichlet DIVJOIN should use the polymorphic similarity path");
        }
    }

    @Test
    void testHistogramSimilarityJoinFallsBackWhenPruningIsEnabled() {
        System.setProperty("probsparql.simjoin.pruning", "true");
        try {
            Model model = ModelFactory.createDefaultModel();
            Resource left = model.createResource(EX_NS + "histPrunedLeft");
            Resource right = model.createResource(EX_NS + "histPrunedRight");

            HistogramValue leftHist = new HistogramValue(
                new double[]{0.0, 1.0, 2.0},
                new double[]{0.6, 0.4}
            );
            HistogramValue rightHist = new HistogramValue(
                new double[]{0.0, 1.0, 2.0},
                new double[]{0.58, 0.42}
            );

            left.addProperty(
                model.createProperty(EX_NS + "dist"),
                model.asRDFNode(NodeFactory.createLiteralDT(leftHist.toString(), HistogramDatatype.INSTANCE))
            );
            right.addProperty(
                model.createProperty(EX_NS + "dist"),
                model.asRDFNode(NodeFactory.createLiteralDT(rightHist.toString(), HistogramDatatype.INSTANCE))
            );

            Query query = QueryFactory.create("""
                PREFIX ex: <http://example.org/>
                SELECT * WHERE {
                  { ex:histPrunedLeft ex:dist ?d1 . }
                  DIVJOIN(?d1, ?d2, 0.3, 0.05)
                  { ex:histPrunedRight ex:dist ?d2 . }
                }
                """, Syntax.syntaxARQ);

            try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
                ResultSet results = qexec.execSelect();
                assertTrue(results.hasNext(),
                    "When pruning is enabled, non-GMM pairs should fall back to polymorphic similarity evaluation");
            }
        } finally {
            System.clearProperty("probsparql.simjoin.pruning");
        }
    }

    private OpSimilarityJoin findSimilarityJoin(Op op) {
        if (op == null) {
            return null;
        }
        if (op instanceof OpSimilarityJoin similarityJoin) {
            return similarityJoin;
        }
        if (op instanceof Op1 op1) {
            return findSimilarityJoin(op1.getSubOp());
        }
        if (op instanceof Op2 op2) {
            OpSimilarityJoin fromLeft = findSimilarityJoin(op2.getLeft());
            return fromLeft != null ? fromLeft : findSimilarityJoin(op2.getRight());
        }
        if (op instanceof OpN opN) {
            for (Op child : opN.getElements()) {
                OpSimilarityJoin found = findSimilarityJoin(child);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (op instanceof OpExt opExt) {
            return findSimilarityJoin(opExt.effectiveOp());
        }
        return null;
    }

    private GMMValue create1DGaussian(double mean, double std) {
        return new GMMValue(
            1,
            1,
            "full",
            new double[]{1.0},
            new double[][]{{mean}},
            new double[][][]{{{std * std}}}
        );
    }
}
