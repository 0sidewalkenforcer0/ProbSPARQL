package org.apache.jena.probsparql;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFDataMgr;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Scanner;
// Regex imports - no longer used since we use JavaCC extension
// import java.util.regex.Matcher;
// import java.util.regex.Pattern;
import org.apache.jena.sparql.engine.QueryIterator;
// import org.apache.jena.sparql.engine.ExecutionContext;  // No longer used
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingFactory;
// import org.apache.jena.sparql.algebra.Algebra;  // No longer used
// import org.apache.jena.sparql.algebra.Op;  // No longer used
// import org.apache.jena.sparql.engine.main.QC;  // No longer used
import org.apache.jena.sparql.core.Var;
import org.apache.jena.graph.Node;
// import org.apache.jena.sparql.core.DatasetGraphFactory;  // No longer used

public class QueryRunner {
    
    private static Scanner scanner = new Scanner(System.in);
    private static boolean interactiveMode = false;
    
    /**
     * Wait for user to press Enter to continue (interactive debug mode).
     */
    private static void waitForStep(String stepName) {
        if (interactiveMode) {
            System.out.println("\n[DEBUG] >>> Press Enter to continue to: " + stepName + "...");
            try {
                scanner.nextLine();
            } catch (Exception e) {
                // Ignore
            }
        }
    }
    
    /**
     * Holds FUSEJOIN metadata extracted from query.
     * New relational semantics: { leftPattern } FUSEJOIN(...) { rightPattern }
     * 
     * @deprecated This class is no longer used since we now use formal JavaCC extension.
     *             The JavaCC parser directly creates ElementFuseJoin objects.
     */
    /*
    static class FuseJoinInfo {
        String leftPattern;   // Left table pattern (SPARQL triple patterns)
        String rightPattern;  // Right table pattern (SPARQL triple patterns)
        String leftVar;
        String rightVar;
        double tolerance;
        String resultVar;
        String modifiedQuery;  // Query with FUSEJOIN syntax replaced
        
        FuseJoinInfo(String leftPattern, String rightPattern, 
                     String left, String right, double tol, String result, String modQuery) {
            this.leftPattern = leftPattern;
            this.rightPattern = rightPattern;
            this.leftVar = left;
            this.rightVar = right;
            this.tolerance = tol;
            this.resultVar = result;
            this.modifiedQuery = modQuery;
        }
    }
    */
    
    /**
     * Holds SIMILARITYJOIN metadata extracted from query.
     * New relational semantics: { leftPattern } SIMILARITYJOIN(...) { rightPattern }
     * 
     * @deprecated This class is no longer used since we now use formal JavaCC extension.
     *             The JavaCC parser directly creates ElementSimilarityJoin objects.
     */
    /*
    static class SimilarityJoinInfo {
        String leftPattern;   // Left table pattern
        String rightPattern;  // Right table pattern
        String leftVar;
        String rightVar;
        double tolerance;
        String modifiedQuery;  // Query with SIMILARITYJOIN syntax replaced
        
        SimilarityJoinInfo(String leftPattern, String rightPattern,
                          String left, String right, double tol, String modQuery) {
            this.leftPattern = leftPattern;
            this.rightPattern = rightPattern;
            this.leftVar = left;
            this.rightVar = right;
            this.tolerance = tol;
            this.modifiedQuery = modQuery;
        }
    }
    */
    
    /**
     * Find the matching closing brace for an opening brace at the given position.
     * Handles nested braces correctly.
     * 
     * @deprecated No longer used - JavaCC parser handles syntax parsing directly.
     */
    /*
    private static int findMatchingBrace(String text, int openPos) {
        if (openPos < 0 || openPos >= text.length() || text.charAt(openPos) != '{') {
            return -1;
        }
        int depth = 1;
        for (int i = openPos + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;  // No matching brace found
    }
    */
    
    /**
     * Find the opening brace that precedes the given position.
     * Walks backwards to find the block before FUSEJOIN/SIMILARITYJOIN.
     * Skips whitespace and comment lines (starting with #).
     * 
     * @deprecated No longer used - JavaCC parser handles syntax parsing directly.
     */
    /*
    private static int findPrecedingBlockStart(String text, int beforePos) {
        // Walk backwards to find the closing brace of the preceding block
        // We need to skip whitespace and comments
        int closePos = -1;
        int i = beforePos - 1;
        
        while (i >= 0) {
            char c = text.charAt(i);
            
            if (c == '}') {
                closePos = i;
                break;
            } else if (c == '\n' || c == '\r') {
                // Skip newlines
                i--;
            } else if (Character.isWhitespace(c)) {
                // Skip other whitespace
                i--;
            } else if (c == '#' || isInCommentLine(text, i)) {
                // Skip to beginning of line (comment line)
                while (i >= 0 && text.charAt(i) != '\n' && text.charAt(i) != '\r') {
                    i--;
                }
            } else {
                // Non-whitespace character that's not a brace or part of comment - no preceding block
                return -1;
            }
        }
        
        if (closePos < 0) {
            return -1;
        }
        
        // Now find the matching open brace
        int depth = 1;
        for (int j = closePos - 1; j >= 0; j--) {
            char c = text.charAt(j);
            if (c == '}') {
                depth++;
            } else if (c == '{') {
                depth--;
                if (depth == 0) {
                    return j;
                }
            }
        }
        return -1;
    }
    */
    
    /**
     * Check if the given position is within a comment line.
     * A comment line starts with # (after any leading whitespace).
     * 
     * @deprecated No longer used - JavaCC parser handles syntax parsing directly.
     */
    /*
    private static boolean isInCommentLine(String text, int pos) {
        // Find the start of the current line
        int lineStart = pos;
        while (lineStart > 0 && text.charAt(lineStart - 1) != '\n' && text.charAt(lineStart - 1) != '\r') {
            lineStart--;
        }
        
        // Check if there's a # before the current position on this line
        for (int i = lineStart; i <= pos; i++) {
            char c = text.charAt(i);
            if (c == '#') {
                return true;
            } else if (!Character.isWhitespace(c)) {
                // Non-whitespace before # means it's not a comment line start
                return false;
            }
        }
        return false;
    }
    */
    
    /**
     * Detect and parse FUSEJOIN with new relational semantics.
     * New pattern: { leftPattern } FUSEJOIN(?leftVar, ?rightVar, tolerance, ?resultVar) { rightPattern }
     * 
     * Also supports old pattern: FUSEJOIN(?leftVar, ?rightVar, tolerance, ?resultVar) { }
     * 
     * @return FuseJoinInfo if FUSEJOIN detected, null otherwise
     * 
     * @deprecated This method is no longer used. The JavaCC parser now directly parses
     *             FUSEJOIN syntax and creates ElementFuseJoin objects. No preprocessing needed.
     */
    /*
    private static FuseJoinInfo preprocessFuseJoin(String queryString) {
        // Match the FUSEJOIN operator with arguments
        Pattern pattern = Pattern.compile(
            "FUSEJOIN\\s*\\(\\s*\\?(\\w+)\\s*,\\s*\\?(\\w+)\\s*,\\s*([0-9.]+)\\s*,\\s*\\?(\\w+)\\s*\\)",
            Pattern.CASE_INSENSITIVE
        );
        
        Matcher matcher = pattern.matcher(queryString);
        if (matcher.find()) {
            String leftVar = matcher.group(1);
            String rightVar = matcher.group(2);
            double tolerance = Double.parseDouble(matcher.group(3));
            String resultVar = matcher.group(4);
            
            int fusejoinStart = matcher.start();
            int fusejoinEnd = matcher.end();
            
            // Look for the right pattern { } after FUSEJOIN(...)
            int rightBlockStart = -1;
            int rightBlockEnd = -1;
            String rightPattern = "";
            
            // Skip whitespace to find opening brace
            for (int i = fusejoinEnd; i < queryString.length(); i++) {
                char c = queryString.charAt(i);
                if (c == '{') {
                    rightBlockStart = i;
                    rightBlockEnd = findMatchingBrace(queryString, i);
                    if (rightBlockEnd > rightBlockStart) {
                        rightPattern = queryString.substring(rightBlockStart + 1, rightBlockEnd).trim();
                    }
                    break;
                } else if (!Character.isWhitespace(c)) {
                    break;
                }
            }
            
            // Look for the left pattern { } before FUSEJOIN
            int leftBlockStart = findPrecedingBlockStart(queryString, fusejoinStart);
            int leftBlockEnd = -1;
            String leftPattern = "";
            
            if (leftBlockStart >= 0) {
                leftBlockEnd = findMatchingBrace(queryString, leftBlockStart);
                if (leftBlockEnd > leftBlockStart) {
                    leftPattern = queryString.substring(leftBlockStart + 1, leftBlockEnd).trim();
                }
            }
            
            // Build the modified query
            // Replace the entire { left } FUSEJOIN(...) { right } with just the combined patterns
            String modifiedQuery;
            if (!leftPattern.isEmpty() && !rightPattern.isEmpty()) {
                // New relational syntax: replace everything with both patterns joined
                // The join will be performed by QueryIterFuseJoin at execution time
                String beforeLeft = queryString.substring(0, leftBlockStart);
                String afterRight = queryString.substring(rightBlockEnd + 1);
                // Combine both patterns - they will be processed as a single BGP initially,
                // then the FuseJoin operator will perform the actual join
                modifiedQuery = beforeLeft + "{ " + leftPattern + "\n" + rightPattern + " }" + afterRight;
            } else if (!rightPattern.isEmpty()) {
                // Old syntax: just remove the FUSEJOIN block
                String beforeFuse = queryString.substring(0, fusejoinStart);
                String afterRight = queryString.substring(rightBlockEnd + 1);
                modifiedQuery = beforeFuse + afterRight;
            } else {
                // No valid patterns found - just remove FUSEJOIN syntax
                modifiedQuery = matcher.replaceFirst("");
            }
            
            return new FuseJoinInfo(leftPattern, rightPattern, leftVar, rightVar, tolerance, resultVar, modifiedQuery);
        }
        
        return null;
    }
    */
    
    /**
     * Detect and parse SIMILARITYJOIN with new relational semantics.
     * New pattern: { leftPattern } SIMILARITYJOIN(?leftVar, ?rightVar, tolerance) { rightPattern }
     * 
     * Also supports old pattern: SIMILARITYJOIN(?leftVar, ?rightVar, tolerance) { }
     * 
     * @return SimilarityJoinInfo if SIMILARITYJOIN detected, null otherwise
     * 
     * @deprecated This method is no longer used. The JavaCC parser now directly parses
     *             SIMILARITYJOIN syntax and creates ElementSimilarityJoin objects. No preprocessing needed.
     */
    /*
    private static SimilarityJoinInfo preprocessSimilarityJoin(String queryString) {
        // Match the SIMILARITYJOIN operator with arguments
        Pattern pattern = Pattern.compile(
            "SIMILARITYJOIN\\s*\\(\\s*\\?(\\w+)\\s*,\\s*\\?(\\w+)\\s*,\\s*([0-9.]+)\\s*\\)",
            Pattern.CASE_INSENSITIVE
        );
        
        Matcher matcher = pattern.matcher(queryString);
        if (matcher.find()) {
            String leftVar = matcher.group(1);
            String rightVar = matcher.group(2);
            double tolerance = Double.parseDouble(matcher.group(3));
            
            int simjoinStart = matcher.start();
            int simjoinEnd = matcher.end();
            
            // Look for the right pattern { } after SIMILARITYJOIN(...)
            int rightBlockStart = -1;
            int rightBlockEnd = -1;
            String rightPattern = "";
            
            // Skip whitespace to find opening brace
            for (int i = simjoinEnd; i < queryString.length(); i++) {
                char c = queryString.charAt(i);
                if (c == '{') {
                    rightBlockStart = i;
                    rightBlockEnd = findMatchingBrace(queryString, i);
                    if (rightBlockEnd > rightBlockStart) {
                        rightPattern = queryString.substring(rightBlockStart + 1, rightBlockEnd).trim();
                    }
                    break;
                } else if (!Character.isWhitespace(c)) {
                    break;
                }
            }
            
            // Look for the left pattern { } before SIMILARITYJOIN
            int leftBlockStart = findPrecedingBlockStart(queryString, simjoinStart);
            int leftBlockEnd = -1;
            String leftPattern = "";
            
            if (leftBlockStart >= 0) {
                leftBlockEnd = findMatchingBrace(queryString, leftBlockStart);
                if (leftBlockEnd > leftBlockStart) {
                    leftPattern = queryString.substring(leftBlockStart + 1, leftBlockEnd).trim();
                }
            }
            
            // Build the modified query
            String modifiedQuery;
            if (!leftPattern.isEmpty() && !rightPattern.isEmpty()) {
                // New relational syntax: replace everything with both patterns joined
                String beforeLeft = queryString.substring(0, leftBlockStart);
                String afterRight = queryString.substring(rightBlockEnd + 1);
                modifiedQuery = beforeLeft + "{ " + leftPattern + "\n" + rightPattern + " }" + afterRight;
            } else if (!rightPattern.isEmpty()) {
                // Old syntax: just remove the SIMILARITYJOIN block
                String beforeSim = queryString.substring(0, simjoinStart);
                String afterRight = queryString.substring(rightBlockEnd + 1);
                modifiedQuery = beforeSim + afterRight;
            } else {
                // No valid patterns found - just remove SIMILARITYJOIN syntax
                modifiedQuery = matcher.replaceFirst("");
            }
            
            return new SimilarityJoinInfo(leftPattern, rightPattern, leftVar, rightVar, tolerance, modifiedQuery);
        }
        
        return null;
    }
    */
    
    public static void main(String[] args) {
        // Check for interactive mode flag
        if (args.length > 0 && args[0].equals("--interactive")) {
            interactiveMode = true;
            System.out.println("\n[DEBUG] ========================================");
            System.out.println("[DEBUG] INTERACTIVE DEBUG MODE ENABLED");
            System.out.println("[DEBUG] Press Enter at each step to continue");
            System.out.println("[DEBUG] ========================================\n");
            // Shift arguments
            String[] newArgs = new String[args.length - 1];
            System.arraycopy(args, 1, newArgs, 0, args.length - 1);
            args = newArgs;
        }
        
        // Initialize ProbSPARQL functions and datatypes
        ProbSPARQL.init();
        if (args.length < 2) {
            System.err.println("Usage: QueryRunner [--interactive] <data-file> <query-file>");
            System.err.println("  --interactive: Enable step-by-step debugging mode");
            System.exit(1);
        }
        
        String dataFile = args[0];
        String queryFile = args[1];
        
        // Load RDF data
        System.out.println("Loading data from: " + dataFile);
        waitForStep("Load RDF data");
        Model model = RDFDataMgr.loadModel(dataFile);
        System.out.println("[DEBUG] ✓ Data loaded: " + model.size() + " triples");
        
        // Read query
        System.out.println("\nLoading query from: " + queryFile);
        waitForStep("Read query file");
        String queryString;
        try {
            queryString = new String(Files.readAllBytes(Paths.get(queryFile)));
            System.out.println("[DEBUG] ✓ Query file read: " + queryString.length() + " characters");
        } catch (Exception e) {
            System.err.println("Error reading query file: " + e.getMessage());
            return;
        }
        
        // Print query content
        printQuery(queryString);
        waitForStep("View original query");
        
        // ========================================================================
        // OLD REGEX-BASED PREPROCESSING (DEPRECATED - NOW USING JAVACC EXTENSION)
        // ========================================================================
        // The JavaCC parser now directly handles FUSEJOIN and SIMILARITYJOIN syntax,
        // creating ElementFuseJoin and ElementSimilarityJoin objects automatically.
        // No preprocessing is needed anymore.
        /*
        // Preprocess FUSEJOIN syntax
        System.out.println("\n[DEBUG] ========================================");
        System.out.println("[DEBUG] Step 1: Preprocessing query syntax");
        System.out.println("[DEBUG] ========================================");
        waitForStep("Preprocess query syntax");
        FuseJoinInfo fuseJoinInfo = preprocessFuseJoin(queryString);
        SimilarityJoinInfo similarityJoinInfo = null;
        
        if (fuseJoinInfo != null) {
            queryString = fuseJoinInfo.modifiedQuery;
            System.out.println("[DEBUG] ✓ FUSEJOIN detected!");
            System.out.println("[DEBUG]   Original query length: " + queryString.length() + " chars");
            System.out.println("[DEBUG]   Left pattern: " + (fuseJoinInfo.leftPattern.isEmpty() ? "(none)" : fuseJoinInfo.leftPattern));
            System.out.println("[DEBUG]   Right pattern: " + (fuseJoinInfo.rightPattern.isEmpty() ? "(none)" : fuseJoinInfo.rightPattern));
            System.out.println("[DEBUG]   Variables: ?" + fuseJoinInfo.leftVar + ", ?" + fuseJoinInfo.rightVar + 
                             " -> ?" + fuseJoinInfo.resultVar);
            System.out.println("[DEBUG]   Tolerance: " + fuseJoinInfo.tolerance);
            System.out.println("[DEBUG]   Modified query:\n" + fuseJoinInfo.modifiedQuery);
        } else {
            // Check for SIMILARITYJOIN if no FUSEJOIN found
            similarityJoinInfo = preprocessSimilarityJoin(queryString);
            if (similarityJoinInfo != null) {
                queryString = similarityJoinInfo.modifiedQuery;
                System.out.println("[DEBUG] ✓ SIMILARITYJOIN detected!");
                System.out.println("[DEBUG]   Left pattern: " + (similarityJoinInfo.leftPattern.isEmpty() ? "(none)" : similarityJoinInfo.leftPattern));
                System.out.println("[DEBUG]   Right pattern: " + (similarityJoinInfo.rightPattern.isEmpty() ? "(none)" : similarityJoinInfo.rightPattern));
                System.out.println("[DEBUG]   Variables: ?" + similarityJoinInfo.leftVar + ", ?" + similarityJoinInfo.rightVar);
                System.out.println("[DEBUG]   Tolerance: " + similarityJoinInfo.tolerance);
                System.out.println("[DEBUG]   Modified query:\n" + similarityJoinInfo.modifiedQuery);
            } else {
                System.out.println("[DEBUG] No FUSEJOIN or SIMILARITYJOIN detected - standard SPARQL query");
            }
        }
        
        // Execute query
        String queryType = fuseJoinInfo != null ? "FUSEJOIN" : 
                          (similarityJoinInfo != null ? "SIMILARITYJOIN" : "Standard");
        */
        
        // NEW APPROACH: Direct parsing with JavaCC - no preprocessing needed
        String queryType = "Standard";  // Will be detected automatically by parser
        System.out.println("\n[DEBUG] ========================================");
        System.out.println("[DEBUG] Step 2: Parsing query");
        System.out.println("[DEBUG] ========================================");
        waitForStep("Parse query");
        System.out.println("[DEBUG] Query type: " + queryType);
        Query query = QueryFactory.create(queryString);
        System.out.println("[DEBUG] ✓ Query parsed successfully");
        System.out.println("[DEBUG]   Result variables: " + query.getResultVars());
        
        System.out.println("\n[DEBUG] ========================================");
        System.out.println("[DEBUG] Step 3: Setting up execution context");
        System.out.println("[DEBUG] ========================================");
        waitForStep("Setup execution context");
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            // ========================================================================
            // OLD CONTEXT-BASED APPROACH (DEPRECATED)
            // ========================================================================
            // With JavaCC extension, FUSEJOIN and SIMILARITYJOIN are parsed directly
            // into ElementFuseJoin and ElementSimilarityJoin objects, which are then
            // compiled into OpFuseJoin and OpSimilarityJoin by AlgebraGenerator.
            // No context variables are needed - the information is in the algebra tree.
            /*
            // Pass FUSEJOIN metadata to execution context
            if (fuseJoinInfo != null) {
                System.out.println("[DEBUG] Setting FUSEJOIN context variables:");
                qexec.getContext().set(ProbSPARQL.FUSEJOIN_LEFT_VAR, fuseJoinInfo.leftVar);
                qexec.getContext().set(ProbSPARQL.FUSEJOIN_RIGHT_VAR, fuseJoinInfo.rightVar);
                qexec.getContext().set(ProbSPARQL.FUSEJOIN_TOLERANCE, fuseJoinInfo.tolerance);
                qexec.getContext().set(ProbSPARQL.FUSEJOIN_RESULT_VAR, fuseJoinInfo.resultVar);
                System.out.println("[DEBUG]   ?" + fuseJoinInfo.leftVar + " -> ?" + fuseJoinInfo.rightVar + 
                                 " (tolerance: " + fuseJoinInfo.tolerance + ") -> ?" + fuseJoinInfo.resultVar);
                // Store pattern information for potential nested loop join implementation
                qexec.getContext().set(ProbSPARQL.FUSEJOIN_LEFT_PATTERN, fuseJoinInfo.leftPattern);
                qexec.getContext().set(ProbSPARQL.FUSEJOIN_RIGHT_PATTERN, fuseJoinInfo.rightPattern);
                System.out.println("[DEBUG]   Left pattern stored: " + (!fuseJoinInfo.leftPattern.isEmpty()));
                System.out.println("[DEBUG]   Right pattern stored: " + (!fuseJoinInfo.rightPattern.isEmpty()));
            }
            
            // Pass SIMILARITYJOIN metadata to execution context
            if (similarityJoinInfo != null) {
                System.out.println("[DEBUG] Setting SIMILARITYJOIN context variables:");
                qexec.getContext().set(ProbSPARQL.SIMILARITYJOIN_LEFT_VAR, similarityJoinInfo.leftVar);
                qexec.getContext().set(ProbSPARQL.SIMILARITYJOIN_RIGHT_VAR, similarityJoinInfo.rightVar);
                qexec.getContext().set(ProbSPARQL.SIMILARITYJOIN_TOLERANCE, similarityJoinInfo.tolerance);
                System.out.println("[DEBUG]   ?" + similarityJoinInfo.leftVar + " -> ?" + similarityJoinInfo.rightVar + 
                                 " (tolerance: " + similarityJoinInfo.tolerance + ")");
                // Store pattern information for potential nested loop join implementation
                qexec.getContext().set(ProbSPARQL.SIMILARITYJOIN_LEFT_PATTERN, similarityJoinInfo.leftPattern);
                qexec.getContext().set(ProbSPARQL.SIMILARITYJOIN_RIGHT_PATTERN, similarityJoinInfo.rightPattern);
                System.out.println("[DEBUG]   Left pattern stored: " + (!similarityJoinInfo.leftPattern.isEmpty()));
                System.out.println("[DEBUG]   Right pattern stored: " + (!similarityJoinInfo.rightPattern.isEmpty()));
            }
            */
            
            System.out.println("[DEBUG] ✓ Execution context ready");
            System.out.println("[DEBUG]   Using JavaCC-based parser - no context variables needed");
            
            System.out.println("\n[DEBUG] ========================================");
            System.out.println("[DEBUG] Step 4: Executing query");
            System.out.println("[DEBUG] ========================================");
            waitForStep("Execute query");
            // Execute query directly using QueryIterator
            QueryIterator queryIterator = executeQueryDirectly(qexec, query, model);
            System.out.println("[DEBUG] ✓ Query iterator created");
            waitForStep("Query iterator created");
            
            // Collect all bindings
            int rowCount = 0;
            java.util.List<Binding> allBindings = new java.util.ArrayList<>();
            
            System.out.println("[DEBUG] Iterating through results...");
            waitForStep("Start iterating results");
            while (queryIterator.hasNext()) {
                Binding binding = queryIterator.nextBinding();
                allBindings.add(binding);
                rowCount++;
                if (rowCount <= 3 || interactiveMode) {
                    System.out.println("[DEBUG]   Row " + rowCount + ": " + binding);
                    if (interactiveMode && rowCount <= 10) {
                        waitForStep("Next result row");
                    }
                }
            }
            queryIterator.close();
            System.out.println("[DEBUG] ✓ Collected " + rowCount + " result bindings");
            
            System.out.println("\n[DEBUG] ========================================");
            System.out.println("[DEBUG] Step 5: Outputting results");
            System.out.println("[DEBUG] ========================================");
            waitForStep("Output results");
            // Output results
            outputResults(query, allBindings);
            System.out.println("\n[DEBUG] Total rows: " + rowCount);
        } catch (Exception e) {
            System.err.println("\n[ERROR] Query execution error: " + e.getMessage());
            System.err.println("[ERROR] Stack trace:");
            e.printStackTrace();
        }
    }
    
    /**
     * Execute query directly using QueryIterator to bypass ResultSet deduplication.
     */
    private static QueryIterator executeQueryDirectly(
            QueryExecution qexec, Query query, Model model) {
        
        try {
            System.out.println("[DEBUG] Creating DatasetGraph from model...");
            waitForStep("Create DatasetGraph");
            org.apache.jena.sparql.core.DatasetGraph dsg = 
                org.apache.jena.sparql.core.DatasetGraphFactory.wrap(model.getGraph());
            System.out.println("[DEBUG] ✓ DatasetGraph created");
            
            Binding initialBinding = BindingFactory.binding();
            System.out.println("[DEBUG] Creating QueryEngineProbabilistic...");
            waitForStep("Create QueryEngineProbabilistic");
            org.apache.jena.sparql.engine.QueryEngineProbabilistic engine = 
                new org.apache.jena.sparql.engine.QueryEngineProbabilistic(
                    query, dsg, initialBinding, qexec.getContext()
                );
            System.out.println("[DEBUG] ✓ QueryEngineProbabilistic created");
            
            System.out.println("[DEBUG] Getting query plan iterator...");
            waitForStep("Get query plan iterator");
            QueryIterator iterator = engine.getPlan().iterator();
            System.out.println("[DEBUG] ✓ Query iterator obtained");
            
            return iterator;
        } catch (Exception e) {
            System.err.println("[ERROR] Failed to execute query directly: " + e.getMessage());
            throw new RuntimeException("Failed to execute query directly: " + e.getMessage(), e);
        }
    }
    
    /**
     * Output results in a formatted table.
     */
    private static void outputResults(Query query, java.util.List<Binding> bindings) {
        java.util.List<String> varNames = query.getResultVars();
        int colWidth = 80;
        
        // Print header
        System.out.print("| ");
        for (String varName : varNames) {
            System.out.printf("%-" + (colWidth - 2) + "s | ", varName);
        }
        System.out.println();
        
        // Print separator
        System.out.print("|");
        for (int i = 0; i < varNames.size(); i++) {
            System.out.print("═".repeat(colWidth) + "|");
        }
        System.out.println();
        
        // Print all rows
        for (Binding binding : bindings) {
            // Check if any value is a GMM (long JSON string)
            boolean hasGMM = false;
            for (String varName : varNames) {
                Var var = Var.alloc(varName);
                Node node = binding.get(var);
                if (node != null && node.isLiteral()) {
                    String datatype = node.getLiteralDatatypeURI();
                    if (datatype != null && datatype.contains("gmmLiteral")) {
                        hasGMM = true;
                        break;
                    }
                }
            }
            
            if (hasGMM) {
                // For rows with GMM, print each variable on a separate line
                for (String varName : varNames) {
                    Var var = Var.alloc(varName);
                    Node node = binding.get(var);
                    String value = (node != null) ? formatNode(node) : "null";
                    System.out.printf("%s = %s%n", varName, value);
                }
                System.out.println("────────────────────────────────────────────────────────────────────────────");
            } else {
                // For normal rows, use table format
                System.out.print("| ");
                for (String varName : varNames) {
                    Var var = Var.alloc(varName);
                    Node node = binding.get(var);
                    String value = (node != null) ? formatNode(node) : "null";
                    
                    // Truncate long values
                    if (value.length() > colWidth - 4) {
                        value = value.substring(0, colWidth - 7) + "...";
                    }
                    System.out.printf("%-" + (colWidth - 2) + "s | ", value);
                }
                System.out.println();
            }
        }
        
        // Print footer
        System.out.print("|");
        for (int i = 0; i < varNames.size(); i++) {
            System.out.print("─".repeat(colWidth) + "|");
        }
        System.out.println();
    }
    
    /**
     * Format a Node for display.
     */
    private static String formatNode(Node node) {
        if (node.isURI()) {
            return node.getURI();
        } else if (node.isLiteral()) {
            return "\"" + node.getLiteralLexicalForm() + "\"^^" + node.getLiteralDatatypeURI();
        } else {
            return node.toString();
        }
    }
    
    /**
     * Print query content with a formatted box.
     */
    private static void printQuery(String queryString) {
        System.out.println("\n╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                              SPARQL QUERY                                    ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════════════════╣");
        
        String[] lines = queryString.split("\n");
        for (String line : lines) {
            if (line.length() > 76) {
                line = line.substring(0, 73) + "...";
            }
            System.out.printf("║ %-76s ║%n", line);
        }
        
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝\n");
    }
}
