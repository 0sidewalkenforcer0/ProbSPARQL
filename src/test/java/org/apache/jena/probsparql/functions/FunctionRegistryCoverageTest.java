package org.apache.jena.probsparql.functions;

import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.probsparql.functions.comparison.HistogramJSD;
import org.apache.jena.probsparql.functions.comparison.JSDMode;
import org.apache.jena.probsparql.functions.comparison.JSDivergence;
import org.apache.jena.probsparql.functions.comparison.KLDivergence;
import org.apache.jena.probsparql.functions.comparison.LastDivJoinStats;
import org.apache.jena.probsparql.functions.comparison.PolyJSD;
import org.apache.jena.probsparql.functions.comparison.SameDistribution;
import org.apache.jena.probsparql.functions.comparison.SameTerm;
import org.apache.jena.probsparql.functions.manipulation.Fuse;
import org.apache.jena.probsparql.functions.manipulation.HistogramMean;
import org.apache.jena.probsparql.functions.manipulation.Map;
import org.apache.jena.probsparql.functions.manipulation.Mean;
import org.apache.jena.probsparql.functions.manipulation.Mix;
import org.apache.jena.probsparql.functions.manipulation.ModeCount;
import org.apache.jena.probsparql.functions.manipulation.Quantile;
import org.apache.jena.probsparql.functions.manipulation.Sample;
import org.apache.jena.probsparql.functions.manipulation.Std;
import org.apache.jena.probsparql.functions.thresholding.CDF;
import org.apache.jena.probsparql.functions.thresholding.HistogramCDF;
import org.apache.jena.probsparql.functions.thresholding.LogCDF;
import org.apache.jena.probsparql.functions.thresholding.LogPDF;
import org.apache.jena.probsparql.functions.thresholding.PDF;
import org.apache.jena.probsparql.functions.transformation.Convolve;
import org.apache.jena.probsparql.functions.transformation.Joint;
import org.apache.jena.probsparql.functions.transformation.LinearTransform;
import org.apache.jena.probsparql.functions.transformation.Marginal;
import org.apache.jena.probsparql.functions.transformation.Multiply;
import org.apache.jena.probsparql.functions.transformation.Scale;
import org.apache.jena.probsparql.functions.transformation.Shift;
import org.apache.jena.sparql.function.FunctionRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the correctness suites against silently going stale.
 *
 * <p>The per-category suites only prove something about the functions they mention.
 * If a new function is registered and nobody writes tests for it, those suites still
 * pass. This class closes that gap by checking the registry against the suites: every
 * registered function must be both reachable by URI and named by at least one test.</p>
 */
class FunctionRegistryCoverageTest {

    /** Every function ProbSPARQL.init() registers, paired with its implementation. */
    private static final java.util.Map<String, Class<?>> EXPECTED_FUNCTIONS = new LinkedHashMap<>();

    static {
        // Thresholding
        EXPECTED_FUNCTIONS.put(PDF.URI, PDF.class);
        EXPECTED_FUNCTIONS.put(CDF.URI, CDF.class);
        EXPECTED_FUNCTIONS.put(LogPDF.URI, LogPDF.class);
        EXPECTED_FUNCTIONS.put(LogCDF.URI, LogCDF.class);
        EXPECTED_FUNCTIONS.put(HistogramCDF.URI, HistogramCDF.class);
        // Comparison
        EXPECTED_FUNCTIONS.put(KLDivergence.URI, KLDivergence.class);
        EXPECTED_FUNCTIONS.put(JSDivergence.URI, JSDivergence.class);
        EXPECTED_FUNCTIONS.put(HistogramJSD.URI, HistogramJSD.class);
        EXPECTED_FUNCTIONS.put(PolyJSD.URI, PolyJSD.class);
        EXPECTED_FUNCTIONS.put(JSDMode.URI, JSDMode.class);
        EXPECTED_FUNCTIONS.put(LastDivJoinStats.URI, LastDivJoinStats.class);
        EXPECTED_FUNCTIONS.put(SameTerm.URI, SameTerm.class);
        EXPECTED_FUNCTIONS.put(SameDistribution.URI, SameDistribution.class);
        // Transformation
        EXPECTED_FUNCTIONS.put(Scale.URI, Scale.class);
        EXPECTED_FUNCTIONS.put(Shift.URI, Shift.class);
        EXPECTED_FUNCTIONS.put(LinearTransform.URI, LinearTransform.class);
        EXPECTED_FUNCTIONS.put(Marginal.URI, Marginal.class);
        EXPECTED_FUNCTIONS.put(Joint.URI, Joint.class);
        EXPECTED_FUNCTIONS.put(Convolve.URI, Convolve.class);
        EXPECTED_FUNCTIONS.put(Multiply.URI, Multiply.class);
        // Manipulation
        EXPECTED_FUNCTIONS.put(Mean.URI, Mean.class);
        EXPECTED_FUNCTIONS.put(Std.URI, Std.class);
        EXPECTED_FUNCTIONS.put(Map.URI, Map.class);
        EXPECTED_FUNCTIONS.put(ModeCount.URI, ModeCount.class);
        EXPECTED_FUNCTIONS.put(Mix.URI, Mix.class);
        EXPECTED_FUNCTIONS.put(Fuse.URI, Fuse.class);
        EXPECTED_FUNCTIONS.put(Quantile.URI, Quantile.class);
        EXPECTED_FUNCTIONS.put(HistogramMean.URI, HistogramMean.class);
        EXPECTED_FUNCTIONS.put(Sample.URI, Sample.class);
    }

    /** Test sources that collectively must mention every registered function. */
    private static final String[] CORRECTNESS_SUITES = {
        "ThresholdingCorrectnessTest.java",
        "ComparisonCorrectnessTest.java",
        "TransformationCorrectnessTest.java",
        "ManipulationCorrectnessTest.java"
    };

    @BeforeAll
    static void setUp() {
        ProbSPARQL.init();
    }

    @Test
    void everyExpectedFunctionIsRegisteredAndResolvable() {
        FunctionRegistry registry = FunctionRegistry.get();
        List<String> missing = new ArrayList<>();
        for (String uri : EXPECTED_FUNCTIONS.keySet()) {
            if (registry.get(uri) == null) {
                missing.add(uri);
            }
        }
        assertTrue(missing.isEmpty(), "Functions not resolvable from the registry: " + missing);
    }

    @Test
    void registeredFunctionsCanBeInstantiated() {
        FunctionRegistry registry = FunctionRegistry.get();
        for (java.util.Map.Entry<String, Class<?>> entry : EXPECTED_FUNCTIONS.entrySet()) {
            assertNotNull(registry.get(entry.getKey()).create(entry.getKey()),
                "Registry could not create " + entry.getValue().getSimpleName());
        }
    }

    @Test
    void allFunctionUrisShareThePublicNamespace() {
        for (String uri : EXPECTED_FUNCTIONS.keySet()) {
            assertTrue(uri.startsWith("http://probsparql.org/function#"),
                "Function URI outside the published namespace: " + uri);
        }
    }

    @Test
    void functionUrisAreDistinct() {
        Set<String> distinct = Set.copyOf(EXPECTED_FUNCTIONS.keySet());
        assertTrue(distinct.size() == EXPECTED_FUNCTIONS.size(),
            "Two functions share a URI, so one would shadow the other in the registry");
    }

    @Test
    void everyRegisteredFunctionIsCoveredByACorrectnessSuite() throws IOException {
        String suites = readCorrectnessSuites();
        List<String> untested = new ArrayList<>();
        for (java.util.Map.Entry<String, Class<?>> entry : EXPECTED_FUNCTIONS.entrySet()) {
            String simpleName = entry.getValue().getSimpleName();
            // The suites construct each function directly, e.g. "new Marginal()".
            if (!suites.contains("new " + simpleName + "(")) {
                untested.add(simpleName);
            }
        }
        if (!untested.isEmpty()) {
            fail("These registered functions have no correctness test; add one to the"
                + " matching *CorrectnessTest before registering them: " + untested);
        }
    }

    /**
     * Reads the correctness suites from the source tree so this check reflects what a
     * developer would have to edit, rather than what happens to be on the classpath.
     */
    private static String readCorrectnessSuites() throws IOException {
        Path dir = locateSuiteDirectory();
        StringBuilder combined = new StringBuilder();
        for (String suite : CORRECTNESS_SUITES) {
            Path file = dir.resolve(suite);
            assertTrue(Files.exists(file), "Missing correctness suite: " + file);
            combined.append(Files.readString(file, StandardCharsets.UTF_8));
        }
        return combined.toString();
    }

    /**
     * Locates this package inside {@code src/test/java}, searching upward so the check
     * works whether the tests run from the module directory or the repository root.
     */
    private static Path locateSuiteDirectory() {
        String relative = "src/test/java/org/apache/jena/probsparql/functions";
        Path base = new File("").getAbsoluteFile().toPath();
        for (Path candidate = base; candidate != null; candidate = candidate.getParent()) {
            Path resolved = candidate.resolve(relative);
            if (Files.isDirectory(resolved)) {
                return resolved;
            }
        }
        throw new IllegalStateException("Could not locate " + relative + " from " + base);
    }

    @Test
    void theExpectedListMatchesTheFunctionsInitRegisters() throws IOException {
        // ProbSPARQL.init() is the single place functions become visible to SPARQL, so
        // a function added there but not here would escape every check above.
        Path source = locateMainSource();
        String init = Files.readString(source, StandardCharsets.UTF_8);
        List<String> unlisted = new ArrayList<>();
        Stream.of(init.split("\\R"))
            .filter(line -> line.contains("functionRegistry.put("))
            .forEach(line -> {
                String token = line.substring(line.indexOf("functionRegistry.put(") + 21);
                String className = token.substring(0, token.indexOf(".URI")).trim();
                boolean known = EXPECTED_FUNCTIONS.values().stream()
                    .anyMatch(c -> c.getSimpleName().equals(className));
                if (!known) {
                    unlisted.add(className);
                }
            });
        if (!unlisted.isEmpty()) {
            fail("ProbSPARQL.init() registers functions this test does not know about,"
                + " so they are untested: " + unlisted);
        }
    }

    private static Path locateMainSource() {
        String relative = "src/main/java/org/apache/jena/probsparql/ProbSPARQL.java";
        Path base = new File("").getAbsoluteFile().toPath();
        for (Path candidate = base; candidate != null; candidate = candidate.getParent()) {
            Path resolved = candidate.resolve(relative);
            if (Files.isRegularFile(resolved)) {
                return resolved;
            }
        }
        throw new IllegalStateException("Could not locate " + relative + " from " + base);
    }
}
