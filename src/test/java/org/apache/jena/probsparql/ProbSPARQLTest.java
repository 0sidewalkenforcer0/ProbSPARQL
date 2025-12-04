package org.apache.jena.probsparql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ProbSPARQL main class.
 */
class ProbSPARQLTest {
    
    @BeforeEach
    void setUp() {
        // Reset initialization state if needed
    }
    
    @Test
    void testInitialization() {
        ProbSPARQL.init();
        assertTrue(ProbSPARQL.isInitialized(), "ProbSPARQL should be initialized");
    }
    
    @Test
    void testMultipleInitialization() {
        ProbSPARQL.init();
        ProbSPARQL.init(); // Should be safe to call multiple times
        assertTrue(ProbSPARQL.isInitialized(), "ProbSPARQL should remain initialized");
    }
    
    @Test
    void testVersion() {
        String version = ProbSPARQL.getVersion();
        assertNotNull(version, "Version should not be null");
        assertFalse(version.isEmpty(), "Version should not be empty");
        assertEquals("1.0.0-SNAPSHOT", version, "Version should match expected value");
    }
}
