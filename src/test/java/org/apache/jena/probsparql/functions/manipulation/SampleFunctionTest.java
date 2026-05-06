package org.apache.jena.probsparql.functions.manipulation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.probsparql.datatypes.DirichletDatatype;
import org.apache.jena.probsparql.datatypes.DirichletValue;
import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.datatypes.HistogramDatatype;
import org.apache.jena.probsparql.datatypes.HistogramValue;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.sparql.expr.NodeValue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SampleFunctionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void setUp() {
        ProbSPARQL.init();
    }

    @Test
    void testSampleGMMReturnsJsonMatrix() throws Exception {
        GMMValue gmm = new GMMValue(
            1, 2, "diag",
            new double[]{1.0},
            new double[][]{{1.0, 2.0}},
            new double[][][]{{{0.25, 0.5}}}
        );

        JsonNode samples = parseSamples(new Sample().exec(gmmLiteral(gmm), NodeValue.makeInteger(4)));

        assertMatrixShape(samples, 4, 2);
        assertFinite(samples);
    }

    @Test
    void testSampleHistogramStaysInsideSupport() throws Exception {
        HistogramValue histogram = new HistogramValue(
            2,
            new double[][]{
                {0.0, 1.0, 2.0},
                {10.0, 20.0, 30.0}
            },
            new double[]{0.1, 0.2, 0.3, 0.4}
        );

        JsonNode samples = parseSamples(new Sample().exec(histogramLiteral(histogram), NodeValue.makeInteger(8)));

        assertMatrixShape(samples, 8, 2);
        for (JsonNode row : samples) {
            double x = row.get(0).asDouble();
            double y = row.get(1).asDouble();
            assertTrue(x >= 0.0 && x <= 2.0);
            assertTrue(y >= 10.0 && y <= 30.0);
        }
    }

    @Test
    void testSampleDirichletRowsStayOnSimplex() throws Exception {
        DirichletValue dirichlet = new DirichletValue(new double[]{2.0, 3.0, 4.0});

        JsonNode samples = parseSamples(new Sample().exec(dirichletLiteral(dirichlet), NodeValue.makeInteger(6)));

        assertMatrixShape(samples, 6, 3);
        for (JsonNode row : samples) {
            double sum = 0.0;
            for (JsonNode value : row) {
                double x = value.asDouble();
                assertTrue(x > 0.0 && x < 1.0);
                sum += x;
            }
            assertEquals(1.0, sum, 1e-12);
        }
    }

    @Test
    void testSampleIsRegisteredAsSparqlFunction() throws Exception {
        Model model = ModelFactory.createDefaultModel();
        String uqNS = "http://example.org/ontology/uncertainty#";
        String exNS = "http://example.org/data/";

        Property hasDistribution = model.createProperty(uqNS + "hasDistribution");
        Resource sensor = model.createResource(exNS + "sensor1");
        GMMValue gmm = new GMMValue(
            1, 1, "full",
            new double[]{1.0},
            new double[][]{{0.0}},
            new double[][][]{{{1.0}}}
        );
        sensor.addProperty(hasDistribution, model.createTypedLiteral(gmm.toJSON(), GMMDatatype.INSTANCE));

        String queryString =
            "PREFIX prob: <http://probsparql.org/function#> \n" +
            "PREFIX uq: <http://example.org/ontology/uncertainty#> \n" +
            "PREFIX ex: <http://example.org/data/> \n" +
            "SELECT ?samples WHERE { \n" +
            "  ex:sensor1 uq:hasDistribution ?dist . \n" +
            "  BIND(prob:sample(?dist, 3) AS ?samples) \n" +
            "}";

        Query query = QueryFactory.create(queryString);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext());
            QuerySolution soln = results.next();
            Literal samplesLiteral = soln.getLiteral("samples");
            assertMatrixShape(MAPPER.readTree(samplesLiteral.getString()), 3, 1);
        }
    }

    @Test
    void testSampleRejectsInvalidCount() {
        GMMValue gmm = new GMMValue(
            1, 1, "full",
            new double[]{1.0},
            new double[][]{{0.0}},
            new double[][][]{{{1.0}}}
        );

        Sample sample = new Sample();
        assertThrows(IllegalArgumentException.class,
            () -> sample.exec(gmmLiteral(gmm), NodeValue.makeInteger(0)));
        assertThrows(IllegalArgumentException.class,
            () -> sample.exec(gmmLiteral(gmm), NodeValue.makeDouble(2.5)));
    }

    private JsonNode parseSamples(NodeValue value) throws Exception {
        assertTrue(value.isString());
        return MAPPER.readTree(value.getString());
    }

    private void assertMatrixShape(JsonNode samples, int rows, int columns) {
        assertTrue(samples.isArray());
        assertEquals(rows, samples.size());
        for (JsonNode row : samples) {
            assertTrue(row.isArray());
            assertEquals(columns, row.size());
        }
    }

    private void assertFinite(JsonNode samples) {
        for (JsonNode row : samples) {
            for (JsonNode value : row) {
                assertTrue(Double.isFinite(value.asDouble()));
            }
        }
    }

    private NodeValue gmmLiteral(GMMValue gmm) {
        Node node = NodeFactory.createLiteralDT(gmm.toJSON(), GMMDatatype.INSTANCE);
        return NodeValue.makeNode(node);
    }

    private NodeValue histogramLiteral(HistogramValue histogram) {
        Node node = NodeFactory.createLiteralDT(histogram.toString(), HistogramDatatype.INSTANCE);
        return NodeValue.makeNode(node);
    }

    private NodeValue dirichletLiteral(DirichletValue dirichlet) {
        Node node = NodeFactory.createLiteralDT(dirichlet.toJSON(), DirichletDatatype.INSTANCE);
        return NodeValue.makeNode(node);
    }
}
