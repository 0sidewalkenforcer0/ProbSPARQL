package org.apache.jena.probsparql.server;

import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFDataMgr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * ProbSPARQL Fuseki Server - HTTP SPARQL Endpoint with Probabilistic Extensions
 * 
 * This server provides:
 * - HTTP SPARQL endpoint at http://localhost:3030/probsparql
 * - All 27 ProbSPARQL functions (prob:fuse, prob:map, prob:mean, etc.)
 * - Property functions for fuzzyJoin and exactJoin
 * - Web UI for query testing
 * - RESTful API for programmatic access
 * 
 * Usage:
 *   java -cp target/jena-probsparql-1.0.0-SNAPSHOT.jar \
 *        org.apache.jena.probsparql.server.ProbSPARQLFuseki
 * 
 * Endpoints:
 *   - SPARQL Query: http://localhost:3030/probsparql/query
 *   - SPARQL Update: http://localhost:3030/probsparql/update
 *   - Web UI: http://localhost:3030/
 * 
 * @author ProbSPARQL Team
 */
public class ProbSPARQLFuseki {
    
    private static final Logger logger = LoggerFactory.getLogger(ProbSPARQLFuseki.class);
    
    private static final int DEFAULT_PORT = 3030;
    private static final String DATASET_NAME = "probsparql";
    private static final String STATIC_UI_DIR = new File("src/main/resources/fuseki-ui").getAbsolutePath();
    
    private FusekiServer server;
    
    /**
     * Start the Fuseki server with ProbSPARQL extensions
     * 
     * @param port HTTP port (default 3030)
     * @param dataFiles Optional RDF data files to preload
     */
    public void start(int port, String... dataFiles) {
        logger.info("╔════════════════════════════════════════════════════════════════╗");
        logger.info("║  ProbSPARQL Fuseki Server                                      ║");
        logger.info("║  Probabilistic SPARQL HTTP Endpoint                            ║");
        logger.info("╚════════════════════════════════════════════════════════════════╝");
        logger.info("");
        
        // Initialize ProbSPARQL functions
        logger.info("Initializing ProbSPARQL extensions...");
        ProbSPARQL.init();
        logger.info("✓ Registered 24 probabilistic functions");
        logger.info("✓ Registered 2 property functions (fuzzyJoin, exactJoin)");
        logger.info("");
        
        // Create dataset
        Dataset dataset = DatasetFactory.createTxnMem();
        Model defaultModel = dataset.getDefaultModel();
        
        // Load data files if provided
        if (dataFiles != null && dataFiles.length > 0) {
            logger.info("Loading RDF data files...");
            for (String dataFile : dataFiles) {
                File file = new File(dataFile);
                if (file.exists()) {
                    logger.info("  Loading: {}", dataFile);
                    RDFDataMgr.read(defaultModel, dataFile);
                } else {
                    logger.warn("  File not found: {}", dataFile);
                }
            }
            logger.info("✓ Loaded {} triples", defaultModel.size());
            logger.info("");
        }
        
        // Build and start Fuseki server
        logger.info("Starting Fuseki server...");

        // Thread pool: scale with CPU cores; override via -DPROBSPARQL_MAX_THREADS=N
        int cores = Runtime.getRuntime().availableProcessors();
        int minThreads = Math.max(4, cores);
        int maxThreads = Integer.getInteger("PROBSPARQL_MAX_THREADS", Math.max(50, cores * 8));
        logger.info("Jetty thread pool: min={}, max={} (cores={})", minThreads, maxThreads, cores);

        server = FusekiServer.create()
            .port(port)
            .numServerThreads(minThreads, maxThreads)
            .staticFileBase(STATIC_UI_DIR)
            .add("/" + DATASET_NAME, dataset)
            .build();
        
        server.start();
        
        logger.info("╔════════════════════════════════════════════════════════════════╗");
        logger.info("║  Server Started Successfully!                                  ║");
        logger.info("╚════════════════════════════════════════════════════════════════╝");
        logger.info("");
        logger.info("SPARQL Endpoint URLs:");
        logger.info("  • Query:  http://localhost:{}/{}/query", port, DATASET_NAME);
        logger.info("  • Update: http://localhost:{}/{}/update", port, DATASET_NAME);
        logger.info("  • Web UI: http://localhost:{}/", port);
        logger.info("");
        logger.info("Available ProbSPARQL Functions:");
        logger.info("  Thresholding:   prob:pdf, prob:cdf, prob:logpdf, prob:logcdf");
        logger.info("  Comparison:     prob:kldivergence, prob:jsd, prob:jsdivergence,");
        logger.info("                  prob:sameTerm, prob:sameDistribution");
        logger.info("  Transformation: prob:scale, prob:shift, prob:linear, prob:marginal,");
        logger.info("                  prob:joint, prob:convolve, prob:multiply");
        logger.info("  Manipulation:   prob:mean, prob:std, prob:map, prob:modecount,");
        logger.info("                  prob:mix, prob:fuse, prob:quantile, prob:sample");
        logger.info("  Property Funcs: probpf:fuzzyJoin, probpf:exactJoin");
        logger.info("");
        logger.info("Example Query:");
        logger.info("  PREFIX prob: <http://probsparql.org/function#>");
        logger.info("  PREFIX uq: <http://example.org/ontology/uncertainty#>");
        logger.info("  SELECT ?rv ?mean WHERE {");
        logger.info("    ?rv uq:hasDistribution ?dist .");
        logger.info("    BIND(prob:mean(?dist) AS ?mean)");
        logger.info("  }");
        logger.info("");
        logger.info("Press Ctrl+C to stop the server");
        logger.info("════════════════════════════════════════════════════════════════");
    }
    
    /**
     * Stop the Fuseki server
     */
    public void stop() {
        if (server != null) {
            logger.info("Stopping Fuseki server...");
            server.stop();
            logger.info("Server stopped");
        }
    }
    
    /**
     * Main entry point
     * 
     * Usage:
     *   java ProbSPARQLFuseki [port] [datafile1] [datafile2] ...
     * 
     * Examples:
     *   # Start with default port 3030, no data
     *   java ProbSPARQLFuseki
     * 
     *   # Start on port 3040
     *   java ProbSPARQLFuseki 3040
     * 
     *   # Start with preloaded data
     *   java ProbSPARQLFuseki 3030 examples/data/angle-grinder-instances.ttl
     */
    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        String[] dataFiles = null;
        
        // Parse command line arguments
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
                if (args.length > 1) {
                    dataFiles = new String[args.length - 1];
                    System.arraycopy(args, 1, dataFiles, 0, dataFiles.length);
                }
            } catch (NumberFormatException e) {
                // First arg is not a port, treat all as data files
                dataFiles = args;
                port = DEFAULT_PORT;
            }
        }
        
        // Start server
        ProbSPARQLFuseki fuseki = new ProbSPARQLFuseki();
        fuseki.start(port, dataFiles);
        
        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println();
            fuseki.stop();
        }));
        
        // Keep server running
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            logger.info("Server interrupted");
        }
    }
}
