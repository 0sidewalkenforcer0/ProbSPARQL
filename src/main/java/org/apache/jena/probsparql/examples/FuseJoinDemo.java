package org.apache.jena.probsparql.examples;

import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

/**
 * Demonstration of the legacy FUSEJOIN algebra operator for Bayesian fusion.
 * 
 * This example shows how to use the FUSEJOIN operator in SPARQL queries
 * to perform probabilistic fusion of GMM distributions based on 
 * Jensen-Shannon divergence compatibility.
 * 
 * Note: This example assumes JavaCC grammar modifications have been applied
 * to Apache Jena's sparql_11.jj file to support FUSEJOIN syntax.
 *
 * @deprecated Legacy prototype demo retained for archival compatibility. It is
 *             not part of the maintained examples or paper benchmarks.
 */
@Deprecated
public class FuseJoinDemo {
    
    private static final String UQ_NS = "http://uncertainty.org/";
    private static final String PROB_NS = "http://probsparql.org/function#";
    
    public static void main(String[] args) {
        // Initialize ProbSPARQL (registers datatypes, functions, operators)
        ProbSPARQL.init();
        
        System.out.println("=== ProbSPARQL FUSEJOIN Algebra Operator Demo ===\n");
        
        // Create sample data with probabilistic distributions
        Model model = createSampleData();
        
        // Example 1: Basic FUSEJOIN
        System.out.println("Example 1: Basic Bayesian Fusion with FUSEJOIN");
        System.out.println("------------------------------------------------");
        runBasicFuseJoin(model);
        
        // Example 2: FUSEJOIN with filtering
        System.out.println("\nExample 2: FUSEJOIN with Posterior Filtering");
        System.out.println("---------------------------------------------");
        runFilteredFuseJoin(model);
        
        // Example 3: Multiple sensor fusion
        System.out.println("\nExample 3: Multi-Sensor Fusion");
        System.out.println("-------------------------------");
        runMultiSensorFusion(model);
    }
    
    /**
     * Create sample RDF data with GMM distributions.
     */
    private static Model createSampleData() {
        Model model = ModelFactory.createDefaultModel();
        
        // Create namespace properties
        Property hasPrior = model.createProperty(UQ_NS + "hasPriorDistribution");
        Property hasMeasurement = model.createProperty(UQ_NS + "hasMeasurement");
        Property hasLabel = RDFS.label;
        
        // Sensor 1: Compatible prior and measurement (small JS divergence)
        Resource sensor1 = model.createResource(UQ_NS + "sensor1");
        sensor1.addProperty(hasLabel, "Temperature Sensor A");
        
        // Prior: N(20.0, 2.0^2) - belief that temperature is around 20°C
        GMMValue prior1 = new GMMValue(
            1, 1, "spherical",
            new double[]{1.0},
            new double[][]{{20.0}},
            new double[][][]{{{4.0}}}
        );
        Literal priorLit1 = model.createTypedLiteral(prior1.toJSON(), GMMDatatype.INSTANCE);
        sensor1.addProperty(hasPrior, priorLit1);
        
        // Measurement: N(21.0, 1.0^2) - measurement suggests 21°C
        GMMValue meas1 = new GMMValue(
            1, 1, "spherical",
            new double[]{1.0},
            new double[][]{{21.0}},
            new double[][][]{{{1.0}}}
        );
        Literal measLit1 = model.createTypedLiteral(meas1.toJSON(), GMMDatatype.INSTANCE);
        sensor1.addProperty(hasMeasurement, measLit1);
        
        // Sensor 2: Incompatible prior and measurement (large JS divergence)
        Resource sensor2 = model.createResource(UQ_NS + "sensor2");
        sensor2.addProperty(hasLabel, "Temperature Sensor B");
        
        // Prior: N(20.0, 2.0^2)
        GMMValue prior2 = new GMMValue(
            1, 1, "spherical",
            new double[]{1.0},
            new double[][]{{20.0}},
            new double[][][]{{{4.0}}}
        );
        Literal priorLit2 = model.createTypedLiteral(prior2.toJSON(), GMMDatatype.INSTANCE);
        sensor2.addProperty(hasPrior, priorLit2);
        
        // Measurement: N(35.0, 1.0^2) - very different from prior (outlier)
        GMMValue meas2 = new GMMValue(
            1, 1, "spherical",
            new double[]{1.0},
            new double[][]{{35.0}},
            new double[][][]{{{1.0}}}
        );
        Literal measLit2 = model.createTypedLiteral(meas2.toJSON(), GMMDatatype.INSTANCE);
        sensor2.addProperty(hasMeasurement, measLit2);
        
        return model;
    }
    
    /**
     * Example 1: Basic FUSEJOIN operation.
     */
    private static void runBasicFuseJoin(Model model) {
        String queryString = 
            "PREFIX prob: <" + PROB_NS + ">\n" +
            "PREFIX uq: <" + UQ_NS + ">\n" +
            "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n" +
            "\n" +
            "SELECT ?label ?meanPrior ?meanMeas ?meanPosterior\n" +
            "WHERE {\n" +
            "    ?sensor rdfs:label ?label .\n" +
            "    ?sensor uq:hasPriorDistribution ?prior .\n" +
            "    ?sensor uq:hasMeasurement ?measurement .\n" +
            "    \n" +
            "    # FUSEJOIN: Algebra operator for Bayesian fusion\n" +
            "    # Only fuses distributions with JS divergence ≤ 0.1\n" +
            "    FUSEJOIN(?prior, ?measurement, 0.1, ?posterior) { }\n" +
            "    \n" +
            "    BIND(prob:mean(?prior) AS ?meanPrior)\n" +
            "    BIND(prob:mean(?measurement) AS ?meanMeas)\n" +
            "    BIND(prob:mean(?posterior) AS ?meanPosterior)\n" +
            "}\n";
        
        System.out.println("Query:");
        System.out.println(queryString);
        System.out.println("\nResults:");
        
        try {
            Query query = QueryFactory.create(queryString);
            QueryExecution qexec = QueryExecutionFactory.create(query, model);
            ResultSet results = qexec.execSelect();
            
            ResultSetFormatter.out(System.out, results, query);
            
            qexec.close();
        } catch (Exception e) {
            System.err.println("Error executing query: " + e.getMessage());
            System.err.println("\nNote: This query requires JavaCC grammar modifications.");
            System.err.println("See docs/JAVACC_GRAMMAR_MODIFICATIONS.md for details.");
        }
    }
    
    /**
     * Example 2: FUSEJOIN with filtering on posterior.
     */
    private static void runFilteredFuseJoin(Model model) {
        String queryString = 
            "PREFIX prob: <" + PROB_NS + ">\n" +
            "PREFIX uq: <" + UQ_NS + ">\n" +
            "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n" +
            "\n" +
            "SELECT ?label ?meanPosterior ?stdPosterior\n" +
            "WHERE {\n" +
            "    ?sensor rdfs:label ?label .\n" +
            "    ?sensor uq:hasPriorDistribution ?prior .\n" +
            "    ?sensor uq:hasMeasurement ?measurement .\n" +
            "    \n" +
            "    FUSEJOIN(?prior, ?measurement, 0.1, ?posterior) {\n" +
            "        # Filter posteriors with low uncertainty\n" +
            "        FILTER(prob:std(?posterior) < 1.5)\n" +
            "    }\n" +
            "    \n" +
            "    BIND(prob:mean(?posterior) AS ?meanPosterior)\n" +
            "    BIND(prob:std(?posterior) AS ?stdPosterior)\n" +
            "}\n";
        
        System.out.println("Query:");
        System.out.println(queryString);
        System.out.println("\nResults:");
        
        try {
            Query query = QueryFactory.create(queryString);
            QueryExecution qexec = QueryExecutionFactory.create(query, model);
            ResultSet results = qexec.execSelect();
            
            ResultSetFormatter.out(System.out, results, query);
            
            qexec.close();
        } catch (Exception e) {
            System.err.println("Error executing query: " + e.getMessage());
            System.err.println("\nNote: This query requires JavaCC grammar modifications.");
        }
    }
    
    /**
     * Example 3: Multi-sensor fusion scenario.
     */
    private static void runMultiSensorFusion(Model model) {
        String queryString = 
            "PREFIX prob: <" + PROB_NS + ">\n" +
            "PREFIX uq: <" + UQ_NS + ">\n" +
            "\n" +
            "SELECT (COUNT(?posterior) AS ?compatibleSensors)\n" +
            "       (AVG(?meanValue) AS ?avgTemperature)\n" +
            "WHERE {\n" +
            "    ?sensor uq:hasPriorDistribution ?prior .\n" +
            "    ?sensor uq:hasMeasurement ?measurement .\n" +
            "    \n" +
            "    FUSEJOIN(?prior, ?measurement, 0.1, ?posterior) { }\n" +
            "    \n" +
            "    BIND(prob:mean(?posterior) AS ?meanValue)\n" +
            "}\n";
        
        System.out.println("Query:");
        System.out.println(queryString);
        System.out.println("\nResults:");
        
        try {
            Query query = QueryFactory.create(queryString);
            QueryExecution qexec = QueryExecutionFactory.create(query, model);
            ResultSet results = qexec.execSelect();
            
            ResultSetFormatter.out(System.out, results, query);
            
            qexec.close();
        } catch (Exception e) {
            System.err.println("Error executing query: " + e.getMessage());
            System.err.println("\nNote: This query requires JavaCC grammar modifications.");
        }
    }
}
