package org.apache.jena.probsparql.functions.comparison;

import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.probsparql.datatypes.HistogramDatatype;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.sparql.expr.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class DivergenceTestTest {
    @BeforeAll static void init() { ProbSPARQL.init(); }
    private NodeValue hist(String weights) {
        return NodeValue.makeNode(ModelFactory.createDefaultModel().createTypedLiteral(
            "{\"bins\":[0,1,2],\"weights\":" + weights + "}", HistogramDatatype.INSTANCE).asNode());
    }
    @Test void exactHistogramDecisionsAndBoundary() {
        var fn = new DivergenceTest();
        var p = hist("[1,0]"); var q = hist("[0,1]");
        var alpha = NodeValue.makeDouble(.05);
        assertTrue(fn.exec(p,p,NodeValue.makeDouble(0),alpha).getBoolean());
        assertFalse(fn.exec(p,q,NodeValue.makeDouble(.1),alpha).getBoolean());
        assertTrue(fn.exec(p,q,NodeValue.makeDouble(1),alpha).getBoolean());
    }
    @Test void invalidArgumentsAreExpressionErrors() {
        var fn = new DivergenceTest(); var p = hist("[1,0]");
        for (double a : new double[]{0,.5,1,Double.NaN})
            assertThrows(ExprEvalException.class, () -> fn.exec(p,p,NodeValue.makeDouble(.1),NodeValue.makeDouble(a)));
        for (double e : new double[]{-1,Double.POSITIVE_INFINITY,Double.NaN})
            assertThrows(ExprEvalException.class, () -> fn.exec(p,p,NodeValue.makeDouble(e),NodeValue.makeDouble(.05)));
        assertThrows(ExprEvalException.class, () -> fn.exec(NodeValue.makeString("bad"),p,NodeValue.makeDouble(.1),NodeValue.makeDouble(.05)));
        assertThrows(ExprEvalException.class, () -> fn.exec(p,p,NodeValue.makeString("0.1"),NodeValue.makeDouble(.05)));
    }
    @Test void gmmSequentialDecision() {
        String old = System.getProperty("probsparql.mode");
        System.setProperty("probsparql.mode", "V3_SPRT");
        try {
            var model = ModelFactory.createDefaultModel();
            var datatype = org.apache.jena.probsparql.datatypes.GMMDatatype.INSTANCE;
            String template = "{\"n_components\":1,\"dimensions\":1,\"covariance_type\":\"diag\",\"weights\":[1],\"means\":[[%s]],\"covariances\":[[1]]}";
            var p = NodeValue.makeNode(model.createTypedLiteral(template.formatted("0"), datatype).asNode());
            var q = NodeValue.makeNode(model.createTypedLiteral(template.formatted("20"), datatype).asNode());
            var fn = new DivergenceTest();
            assertTrue(fn.exec(p,p,NodeValue.makeDouble(.1),NodeValue.makeDouble(.05)).getBoolean());
            assertFalse(fn.exec(p,q,NodeValue.makeDouble(.1),NodeValue.makeDouble(.05)).getBoolean());
        } finally {
            if (old == null) System.clearProperty("probsparql.mode");
            else System.setProperty("probsparql.mode", old);
        }
    }
    @Test void registeredFunctionWorksInSparql() {
        String query = "PREFIX prob: <http://probsparql.org/function#> SELECT ?ok WHERE { BIND(prob:divergenceTest(?p,?p,0,0.05) AS ?ok) }";
        var bindings = new QuerySolutionMap();
        var model = ModelFactory.createDefaultModel();
        bindings.add("p", model.asRDFNode(hist("[1,0]").asNode()));
        try (var exec = QueryExecution.model(model).query(query).substitution(bindings).build()) {
            assertTrue(exec.execSelect().next().getLiteral("ok").getBoolean());
        }
    }
}
