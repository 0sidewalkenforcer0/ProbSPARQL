package org.apache.jena.probsparql.datatypes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.probsparql.ProbSPARQL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GMMEqualityEndToEndTest {

    private static final String GMM_AB =
            "{\"n_components\":2,\"dimensions\":1,\"covariance_type\":\"full\",\"weights\":[0.4,0.6],\"means\":[[10.0],[20.0]],\"covariances\":[[[1.0]],[[2.0]]]}";

    private static final String GMM_BA =
            "{\"n_components\":2,\"dimensions\":1,\"covariance_type\":\"full\",\"weights\":[0.6,0.4],\"means\":[[20.0],[10.0]],\"covariances\":[[[2.0]],[[1.0]]]}";

    private static final String GMM_CD =
            "{\"n_components\":3,\"dimensions\":1,\"covariance_type\":\"full\",\"weights\":[0.2,0.5,0.3],\"means\":[[5.0],[15.0],[25.0]],\"covariances\":[[[0.5]],[[1.5]],[[2.5]]]}";

    private static final String GMM_DCB =
            "{\"n_components\":3,\"dimensions\":1,\"covariance_type\":\"full\",\"weights\":[0.3,0.5,0.2],\"means\":[[25.0],[15.0],[5.0]],\"covariances\":[[[2.5]],[[1.5]],[[0.5]]]}";

    private static final String GMM_OTHER =
            "{\"n_components\":3,\"dimensions\":1,\"covariance_type\":\"full\",\"weights\":[0.2,0.5,0.3],\"means\":[[5.0],[15.0],[26.0]],\"covariances\":[[[0.5]],[[1.5]],[[2.5]]]}";

    @BeforeAll
    static void init() {
        ProbSPARQL.init();
    }

    @Test
    void ordinarySharedVariableJoinTreatsPermutedGmmsAsIdentical(@TempDir Path tempDir) throws IOException {
        Path ttlFile = tempDir.resolve("gmm-equality.ttl");
        Path queryFile = tempDir.resolve("gmm-equality.rq");

        Files.writeString(ttlFile, """
            PREFIX ex: <http://example.org/data/>
            PREFIX uq: <http://example.org/ontology/uncertainty#>

            ex:a1 uq:hasDistribution %s .
            ex:a2 uq:hasDistribution %s .
            ex:b1 uq:hasDistribution %s .
            ex:b2 uq:hasDistribution %s .
            ex:c1 uq:hasDistribution %s .
            """.formatted(
                typedGmmLiteral(GMM_AB),
                typedGmmLiteral(GMM_BA),
                typedGmmLiteral(GMM_CD),
                typedGmmLiteral(GMM_DCB),
                typedGmmLiteral(GMM_OTHER))
        );

        Files.writeString(queryFile, """
            PREFIX uq: <http://example.org/ontology/uncertainty#>
            PREFIX ex: <http://example.org/data/>

            SELECT ?s ?t WHERE {
              ?s uq:hasDistribution ?g .
              ?t uq:hasDistribution ?g .
              FILTER(str(?s) < str(?t))
            }
            ORDER BY ?s ?t
            """);

        Model model = RDFDataMgr.loadModel(ttlFile.toString());
        Query query = QueryFactory.read(queryFile.toString());

        Set<String> pairs = new HashSet<>();
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet rs = qexec.execSelect();
            while (rs.hasNext()) {
                QuerySolution sol = rs.next();
                pairs.add(sol.getResource("s").getLocalName() + "-" + sol.getResource("t").getLocalName());
            }
        }

        assertEquals(2, pairs.size());
        assertTrue(pairs.contains("a1-a2"));
        assertTrue(pairs.contains("b1-b2"));
    }

    private static String typedGmmLiteral(String json) {
        return "\"" + json.replace("\\", "\\\\").replace("\"", "\\\"") + "\"^^uq:gmmLiteral";
    }
}
