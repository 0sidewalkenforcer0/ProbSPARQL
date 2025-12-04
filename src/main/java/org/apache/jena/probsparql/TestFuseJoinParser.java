package org.apache.jena.probsparql;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;

public class TestFuseJoinParser {
    public static void main(String[] args) {
        ProbSPARQL.init();
        
        String queryStr = args[0];
        try {
            Query query = QueryFactory.create(queryStr);
            System.out.println("✓ Query parsed successfully!");
            System.out.println(query);
        } catch (Exception e) {
            System.err.println("✗ Parse error:");
            e.printStackTrace();
        }
    }
}
