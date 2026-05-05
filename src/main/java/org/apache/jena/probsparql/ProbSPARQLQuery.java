package org.apache.jena.probsparql;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.RDFDataMgr;

import java.nio.file.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lightweight CLI runner: loads TTL files and executes a SPARQL query file.
 * No HTTP server needed.
 *
 * Usage:
 *   mvn exec:java -Dexec.mainClass=org.apache.jena.probsparql.ProbSPARQLQuery \
 *     -Dexec.args="query.sparql data1.ttl data2.ttl ..."
 */
public class ProbSPARQLQuery {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: ProbSPARQLQuery <query.sparql> <data.ttl> [data2.ttl ...]");
            System.exit(1);
        }

        // Initialize ProbSPARQL custom functions (prob:jsd, etc.)
        ProbSPARQL.init();

        // Load all TTL data files into one in-memory model
        Model model = ModelFactory.createDefaultModel();
        for (int i = 1; i < args.length; i++) {
            System.err.println("Loading: " + args[i]);
            RDFDataMgr.read(model, args[i]);
        }
        System.err.println("Loaded " + model.size() + " triples.\n");

        // Read SPARQL query from file
        String queryStr = Files.readString(Path.of(args[0]));

        // Execute query and print results
        try (QueryExecution qe = QueryExecutionFactory.create(queryStr, model)) {
            ResultSet rs = qe.execSelect();
            List<String> vars = rs.getResultVars();

            // Determine if top-1-per-group mode: query projected "unknownGrinder"
            // and results are already ORDER BY ?unknownGrinder DESC(?finalScore).
            boolean topOneMode = vars.contains("unknownGrinder");

            List<String> displayVars = vars;

            // Header
            System.out.println(String.join(" | ", displayVars));
            System.out.println("-".repeat(80));

            // Rows — in top-1 mode keep first occurrence of each unknownGrinder
            Set<String> seen = new HashSet<>();
            int count = 0;
            while (rs.hasNext()) {
                QuerySolution sol = rs.nextSolution();

                if (topOneMode) {
                    RDFNode grinderNode = sol.get("unknownGrinder");
                    String grinderKey = grinderNode != null ? grinderNode.toString() : "";
                    if (seen.contains(grinderKey)) continue;
                    seen.add(grinderKey);
                }

                StringBuilder row = new StringBuilder();
                for (String var : displayVars) {
                    if (row.length() > 0) row.append(" | ");
                    RDFNode node = sol.get(var);
                    row.append(node != null ? node.toString() : "(null)");
                }
                System.out.println(row);
                count++;
            }
            System.out.println("-".repeat(80));
            System.out.println(count + " result(s).");
        }
    }
}
