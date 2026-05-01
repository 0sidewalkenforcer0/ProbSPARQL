package org.apache.jena.probsparql;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.Syntax;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
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
              SIMILARITYJOIN(?d1, ?d2, 0.3, 0.05)
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
                  SIMILARITYJOIN(?d1, ?d2, 0.3, 0.05)
                  { ex:right ex:dist ?d2 . }
                }
                """, Syntax.syntaxARQ);

            try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
                ResultSet results = qexec.execSelect();
                assertTrue(results.hasNext(),
                    "A four-argument SIMILARITYJOIN query should execute and return the similar pair");
            }
        } finally {
            System.clearProperty("probsparql.mode");
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
