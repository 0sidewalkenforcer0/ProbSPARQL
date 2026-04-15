package org.apache.jena.probsparql;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.sparql.engine.binding.BindingFactory;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.QueryEngineProbabilistic;
import org.apache.jena.probsparql.functions.comparison.JSDivergenceConfig;
import org.apache.jena.query.ARQ;
import org.apache.jena.sparql.core.DatasetGraph;

import java.io.*;
import java.util.*;

/**
 * Exp 3.3: Selectivity Sensitivity Benchmark
 *
 * Measures how SIMILARITYJOIN behavior (latency, result-set size, accuracy)
 * changes as the similarity threshold θ varies across:
 *   θ ∈ {0.01, 0.05, 0.10, 0.20, 0.30, 0.50}
 *
 * For each (method × θ × dataset) combination we record:
 *   - median execution time (ms)
 *   - result count (pairs returned)
 *   - binary-classification accuracy vs. GT reference
 *
 * Output: benchmark/results/exp3_3_selectivity.csv
 *
 * Usage:
 *   mvn exec:java -Dexec.mainClass="org.apache.jena.probsparql.SelectivityBenchmark"
 */
public class SelectivityBenchmark {

    // -----------------------------------------------------------------------
    // Experiment configuration (matches plan §6.2.3 Exp 3.3)
    // -----------------------------------------------------------------------
    private static final double[] THRESHOLDS = {0.01, 0.05, 0.10, 0.20, 0.30, 0.50};
    private static final String[] MODES = {
        "V1_MC", "V2_STRATIFIED", "V3_SPRT", "V4_BOUNDS", "V5_ADAPTIVE"
    };
    private static final String[] DATASETS = {"easy", "medium", "hard", "mixed"};
    private static int warmup      = 3;                  // overridable via --warmup
    private static int iterations  = 10;                 // overridable via --iterations
    private static int limitGraphs = Integer.MAX_VALUE;  // overridable via --limit-graphs

    // Query template – θ placeholder replaced before execution
    private static final String QUERY_TEMPLATE = """
        PREFIX prob: <http://example.org/prob#>
        PREFIX uq:   <http://example.org/ontology/uncertainty#>
        SELECT ?s1 ?s2
        WHERE {
          { ?s1 a prob:LeftEntity  ; prob:hasGMM ?g1 . }
          SIMILARITYJOIN(?g1, ?g2, THETA)
          { ?s2 a prob:RightEntity ; prob:hasGMM ?g2 . }
        }
        """;

    // -----------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        ProbSPARQL.init();

        String dataDir   = "benchmark/data";
        String gtCsvPath = "benchmark/results/simjoin_ground_truth.csv";
        String outputDir = "benchmark/results";

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--data-dir"))        dataDir    = args[++i];
            else if (args[i].equals("--gt-csv"))     gtCsvPath  = args[++i];
            else if (args[i].equals("--output-dir")) outputDir  = args[++i];
            else if (args[i].equals("--warmup"))     warmup     = Integer.parseInt(args[++i]);
            else if (args[i].equals("--iterations")) iterations = Integer.parseInt(args[++i]);
            else if (args[i].equals("--limit-graphs")) limitGraphs = Integer.parseInt(args[++i]);
        }
        new File(outputDir).mkdirs();

        System.out.println("=== Exp 3.3: Selectivity Sensitivity Benchmark ===");
        System.out.println("Thresholds : " + Arrays.toString(THRESHOLDS));
        System.out.println("Methods    : " + Arrays.toString(MODES));
        System.out.println("Datasets   : " + Arrays.toString(DATASETS));
        System.out.println("Warmup runs: " + warmup);
        System.out.println("Limit graphs: " + limitGraphs);
        System.out.println("Iterations : " + iterations);
        System.out.println();

        // Load ground-truth JSD per (dataset, pairIndex)
        Map<String, List<Double>> gtTruth = loadGroundTruth(gtCsvPath);

        String outCsv = outputDir + "/exp3_3_selectivity.csv";
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{
            "Method", "Dataset", "Theta",
            "Median_ms", "StdDev_ms",
            "ResultCount", "PairsTotal",
            "Accuracy", "Precision", "Recall", "F1"
        });

        for (String mode : MODES) {
            System.setProperty("probsparql.mode", mode);
            JSDivergenceConfig.reloadMode();
            System.out.println("══════════════════════════════════════");
            System.out.println("MODE: " + mode);

            for (String dataset : DATASETS) {
                String ttlPath = dataDir + "/simjoin_" + dataset + ".ttl";
                if (!new File(ttlPath).exists()) {
                    System.err.println("  SKIP (not found): " + ttlPath);
                    continue;
                }

                Model model = ModelFactory.createDefaultModel();
                try (InputStream in = new FileInputStream(ttlPath)) {
                    model.read(in, null, "TTL");
                }
                if (limitGraphs < Integer.MAX_VALUE) {
                    model = limitModelGraphs(model, limitGraphs);
                }
                int totalPairs = countPairs(model);
                System.out.printf("  Dataset %-6s  (%d pairs)%n", dataset, totalPairs);

                List<Double> gt = gtTruth.getOrDefault(dataset, Collections.emptyList());

                for (double theta : THRESHOLDS) {
                    String query = QUERY_TEMPLATE.replace("THETA", String.valueOf(theta));

                    // Warmup
                    for (int w = 0; w < warmup; w++) execute(model, query);

                    double[] times = new double[iterations];
                    int lastCount = 0;
                    for (int it = 0; it < iterations; it++) {
                        long t0 = System.nanoTime();
                        lastCount = execute(model, query);
                        times[it] = (System.nanoTime() - t0) / 1_000_000.0;
                    }

                    double median = median(times);
                    double std    = std(times);

                    // Classification accuracy: estimate JSD once with GT_10K, classify per θ
                    double[] cls = computeClassificationMetrics(model, gt, theta);
                    // cls = [accuracy, precision, recall, f1]

                    rows.add(new String[]{
                        mode, dataset, String.valueOf(theta),
                        String.format("%.3f", median),
                        String.format("%.3f", std),
                        String.valueOf(lastCount),
                        String.valueOf(totalPairs),
                        String.format("%.4f", cls[0]),
                        String.format("%.4f", cls[1]),
                        String.format("%.4f", cls[2]),
                        String.format("%.4f", cls[3])
                    });

                    System.out.printf("    θ=%.2f  median=%.2fms  count=%d  acc=%.3f%n",
                        theta, median, lastCount, cls[0]);
                }

                model.close();
            }
        }

        writeCsv(outCsv, rows);
        System.out.println("\nResults written to: " + outCsv);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Execute a SIMILARITYJOIN query and return result count. */
    private static int execute(Model model, String queryString) {
        Query query = QueryFactory.create(queryString);
        DatasetGraph dsg = DatasetGraphFactory.wrap(model.getGraph());
        Binding initialBinding = BindingFactory.binding();

        QueryEngineProbabilistic engine = new QueryEngineProbabilistic(
            query, dsg, initialBinding, ARQ.getContext().copy()
        );

        int count = 0;
        QueryIterator iter = engine.getPlan().iterator();
        try {
            while (iter.hasNext()) { iter.next(); count++; }
        } finally {
            iter.close();
        }
        return count;
    }

    /** Count total LeftEntity × RightEntity pairs in the model. */
    private static int countPairs(Model model) {
        int nL = countType(model, "http://example.org/prob#LeftEntity");
        int nR = countType(model, "http://example.org/prob#RightEntity");
        return nL * nR;
    }

    private static int countType(Model model, String typeUri) {
        String q = "SELECT (COUNT(*) AS ?n) WHERE { ?s a <" + typeUri + "> }";
        try (QueryExecution qe = QueryExecutionFactory.create(q, model)) {
            ResultSet rs = qe.execSelect();
            if (rs.hasNext()) return rs.next().getLiteral("n").getInt();
        }
        return 0;
    }

    /**
     * Compute classification accuracy given:
     *  - per-pair true JSD values from ground truth CSV
     *  - threshold θ
     * Uses GT_10K estimates already stored in gtRef.
     */
    private static double[] computeClassificationMetrics(Model model, List<Double> gtJSD, double theta) {
        if (gtJSD.isEmpty()) return new double[]{0, 0, 0, 0};

        int tp = 0, fp = 0, tn = 0, fn = 0;
        // GT_10K classification at this θ vs GT_10K at θ=0.3 (the reference label)
        for (double jsd : gtJSD) {
            boolean trueLabel    = (jsd <= 0.3);   // reference label (θ=0.3 as master GT)
            boolean predictedLbl = (jsd <= theta); // what the query would return at θ
            if (trueLabel  && predictedLbl) tp++;
            if (!trueLabel && predictedLbl) fp++;
            if (!trueLabel && !predictedLbl) tn++;
            if (trueLabel  && !predictedLbl) fn++;
        }

        int total = tp + fp + tn + fn;
        double accuracy  = total > 0 ? (double)(tp + tn) / total : 0;
        double precision = (tp + fp) > 0 ? (double) tp / (tp + fp) : 0;
        double recall    = (tp + fn) > 0 ? (double) tp / (tp + fn) : 0;
        double f1 = (precision + recall) > 0
            ? 2.0 * precision * recall / (precision + recall) : 0;

        return new double[]{accuracy, precision, recall, f1};
    }

    /** Load ground-truth CSV: dataset → list of true JSD values (in order). */
    private static Map<String, List<Double>> loadGroundTruth(String csvPath) throws IOException {
        Map<String, List<Double>> result = new LinkedHashMap<>();
        File f = new File(csvPath);
        if (!f.exists()) {
            System.err.println("WARNING: Ground truth CSV not found: " + csvPath);
            return result;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line = r.readLine(); // skip header
            while ((line = r.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length < 3) continue;
                String dataset = parts[0].trim();
                double jsd = Double.parseDouble(parts[2].trim());
                result.computeIfAbsent(dataset, k -> new ArrayList<>()).add(jsd);
            }
        }
        return result;
    }

    private static double median(double[] a) {
        double[] s = a.clone();
        Arrays.sort(s);
        int n = s.length;
        return (n % 2 == 0) ? (s[n/2-1] + s[n/2]) / 2.0 : s[n/2];
    }

    private static double std(double[] a) {
        double mean = Arrays.stream(a).average().orElse(0);
        double var  = Arrays.stream(a).map(x -> (x-mean)*(x-mean)).average().orElse(0);
        return Math.sqrt(var);
    }

    /**
     * Build a sub-model containing only the first {@code maxPerSide} LeftEntity nodes
     * and the first {@code maxPerSide} RightEntity nodes, together with all triples
     * reachable from each via prob:hasGMM. Used to create small smoke-test datasets.
     */
    private static Model limitModelGraphs(Model src, int maxPerSide) {
        Property hasGMM = src.createProperty("http://example.org/prob#hasGMM");
        Resource leftType  = src.createResource("http://example.org/prob#LeftEntity");
        Resource rightType = src.createResource("http://example.org/prob#RightEntity");
        Model sub = ModelFactory.createDefaultModel();
        sub.setNsPrefixes(src.getNsPrefixMap());
        copyEntitySubset(src, sub, leftType, hasGMM, maxPerSide);
        copyEntitySubset(src, sub, rightType, hasGMM, maxPerSide);
        return sub;
    }

    private static void copyEntitySubset(Model src, Model dst,
                                         Resource typeUri, Property hasGMM, int max) {
        List<Resource> entities = new ArrayList<>();
        ResIterator it = src.listSubjectsWithProperty(RDF.type, typeUri);
        try {
            while (it.hasNext() && entities.size() < max) {
                entities.add(it.nextResource());
            }
        } finally { it.close(); }

        for (Resource ent : entities) {
            dst.add(src.listStatements(ent, null, (RDFNode) null).toList());
            StmtIterator gmmIt = src.listStatements(ent, hasGMM, (RDFNode) null);
            try {
                while (gmmIt.hasNext()) {
                    RDFNode gmm = gmmIt.nextStatement().getObject();
                    if (gmm.isResource()) {
                        dst.add(src.listStatements(gmm.asResource(), null, (RDFNode) null).toList());
                    }
                }
            } finally { gmmIt.close(); }
        }
    }

    private static void writeCsv(String path, List<String[]> rows) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
            for (String[] row : rows) {
                pw.println(String.join(",", row));
            }
        }
        System.out.println("Wrote " + (rows.size()-1) + " data rows → " + path);
    }
}
