package org.apache.jena.probsparql.app;

import org.apache.jena.probsparql.server.ProbSPARQLFuseki;

import java.awt.Desktop;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

/**
 * Desktop application entry point for ProbSPARQL.
 *
 * Starts the Fuseki server, waits until it is ready, then opens
 * the default system browser to the query UI. This class is the
 * main class used by the jpackage / fat-JAR distribution so that
 * end-users can double-click the app without needing Maven or a
 * separate terminal.
 *
 * Usage (fat JAR):
 *   java -jar probsparql-app.jar [port] [datafile...]
 */
public class AppLauncher {

    private static final int DEFAULT_PORT = 3030;
    private static final int POLL_INTERVAL_MS = 500;
    private static final int MAX_WAIT_MS = 30_000;

    public static void main(String[] args) throws Exception {

        // --- Parse arguments (same convention as ProbSPARQLFuseki.main) ---
        int port = DEFAULT_PORT;
        String[] dataFiles = null;

        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
                if (args.length > 1) {
                    dataFiles = new String[args.length - 1];
                    System.arraycopy(args, 1, dataFiles, 0, dataFiles.length);
                }
            } catch (NumberFormatException e) {
                dataFiles = args;
            }
        }

        final int serverPort = port;
        final String[] serverDataFiles = dataFiles;

        // --- Start Fuseki in a background thread ---
        ProbSPARQLFuseki fuseki = new ProbSPARQLFuseki();

        // Pass max-threads system property into the server thread if set
        String maxThreadsEnv = System.getenv("PROBSPARQL_MAX_THREADS");
        if (maxThreadsEnv != null && System.getProperty("PROBSPARQL_MAX_THREADS") == null) {
            System.setProperty("PROBSPARQL_MAX_THREADS", maxThreadsEnv);
        }

        Thread serverThread = new Thread(() -> {
            fuseki.start(serverPort, serverDataFiles);
        }, "probsparql-server");
        serverThread.setDaemon(false);
        serverThread.start();

        // Graceful shutdown on Ctrl-C or app quit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[ProbSPARQL] Shutting down...");
            fuseki.stop();
        }, "probsparql-shutdown"));

        // --- Wait until the server responds on /probsparql/query ---
        String healthUrl = "http://localhost:" + serverPort + "/probsparql/query?query=ASK%7B%7D";
        System.out.println("[ProbSPARQL] Waiting for server to be ready...");

        boolean ready = false;
        long deadline = System.currentTimeMillis() + MAX_WAIT_MS;

        while (System.currentTimeMillis() < deadline) {
            if (isReachable(healthUrl)) {
                ready = true;
                break;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }

        if (!ready) {
            System.err.println("[ProbSPARQL] Server did not start within "
                    + (MAX_WAIT_MS / 1000) + "s. Opening browser anyway.");
        }

        // --- Open browser ---
        URI queryUri = new URI("http://localhost:" + serverPort + "/");
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(queryUri);
            System.out.println("[ProbSPARQL] Browser opened: " + queryUri);
        } else {
            System.out.println("[ProbSPARQL] Open your browser at: " + queryUri);
        }

        // --- Block main thread so the JVM (and server) stay alive ---
        serverThread.join();
    }

    private static boolean isReachable(String urlString) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setConnectTimeout(300);
            connection.setReadTimeout(300);
            connection.setRequestMethod("GET");
            int code = connection.getResponseCode();
            return code >= 200 && code < 500;
        } catch (Exception e) {
            return false;
        }
    }
}
