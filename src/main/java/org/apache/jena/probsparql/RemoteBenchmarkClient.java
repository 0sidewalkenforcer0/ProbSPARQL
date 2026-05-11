package org.apache.jena.probsparql;

import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTP;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Small helper for benchmark runners that execute against a remote Fuseki
 * endpoint instead of an in-process Jena Dataset.
 */
final class RemoteBenchmarkClient {
    private RemoteBenchmarkClient() {}

    static String endpointFor(String endpointTemplate, String datasetName) {
        if (endpointTemplate == null || endpointTemplate.isBlank()) {
            throw new IllegalArgumentException("Missing required --endpoint-template");
        }
        String encoded = URLEncoder.encode(datasetName, StandardCharsets.UTF_8);
        if (endpointTemplate.contains("{dataset}")) {
            return endpointTemplate.replace("{dataset}", encoded);
        }
        if (endpointTemplate.contains("{name}")) {
            return endpointTemplate.replace("{name}", encoded);
        }
        if (endpointTemplate.contains("%s")) {
            return String.format(endpointTemplate, encoded);
        }
        return endpointTemplate;
    }

    static int execCount(String endpoint, String sparql) {
        final int[] rows = {0};
        forEachSolution(endpoint, sparql, sol -> rows[0]++);
        return rows[0];
    }

    static QueryStats execStats(String endpoint, String sparql, String distinctVar) {
        final int[] rows = {0};
        Set<String> distinct = new LinkedHashSet<>();
        forEachSolution(endpoint, sparql, sol -> {
            rows[0]++;
            if (distinctVar != null && sol.contains(distinctVar)) {
                distinct.add(sol.get(distinctVar).toString());
            }
        });
        return new QueryStats(rows[0], distinct.size());
    }

    static void forEachSolution(String endpoint, String sparql, Consumer<QuerySolution> consumer) {
        try (QueryExecution qe = QueryExecutionHTTP.service(endpoint, sparql)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                consumer.accept(rs.next());
            }
        }
    }

    record QueryStats(int rows, int distinct) {}
}
