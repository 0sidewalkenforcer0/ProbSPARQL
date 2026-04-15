package org.apache.jena.probsparql.datatypes;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.datatypes.TypeMapper;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.DatatypeFormatException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GMMDatatype.
 * 
 * Tests cover:
 * - Datatype registration
 * - Valid JSON parsing
 * - Field order validation
 * - Format error handling
 * - Value serialization
 * 
 * @author ProbSPARQL Team
 */
class GMMDatatypeTest {
    
    @BeforeAll
    static void setUp() {
        // Initialize ProbSPARQL to register custom datatypes
        ProbSPARQL.init();
    }
    
    @Test
    void testDatatypeRegistration() {
        TypeMapper tm = TypeMapper.getInstance();
        RDFDatatype dt = tm.getSafeTypeByName(GMMDatatype.URI);
        
        assertNotNull(dt, "GMMDatatype should be registered");
        assertEquals(GMMDatatype.URI, dt.getURI());
        assertSame(GMMDatatype.INSTANCE, dt);
    }
    
    @Test
    void testValidGMMParsing_SingleGaussian() {
        String json = "{\"n_components\":1,\"dimensions\":1,\"covariance_type\":\"full\"," +
                     "\"weights\":[1.0],\"means\":[[6.02]],\"covariances\":[[[0.16]]]}";
        
        GMMValue gmm = (GMMValue) GMMDatatype.INSTANCE.parse(json);
        
        assertNotNull(gmm);
        assertEquals(1, gmm.getNComponents());
        assertEquals(1, gmm.getDimensions());
        assertEquals("full", gmm.getCovarianceType());
        assertArrayEquals(new double[]{1.0}, gmm.getWeights(), 1e-9);
        assertArrayEquals(new double[]{6.02}, gmm.getMeans()[0], 1e-9);
        assertEquals(0.16, gmm.getCovariances()[0][0][0], 1e-9);
    }
    
    @Test
    void testValidGMMParsing_BimodalDistribution() {
        String json = "{\"n_components\":2,\"dimensions\":1,\"covariance_type\":\"full\"," +
                     "\"weights\":[0.6,0.4]," +
                     "\"means\":[[5.93],[6.15]]," +
                     "\"covariances\":[[[0.04]],[[0.09]]]}";
        
        GMMValue gmm = (GMMValue) GMMDatatype.INSTANCE.parse(json);
        
        assertNotNull(gmm);
        assertEquals(2, gmm.getNComponents());
        assertEquals(1, gmm.getDimensions());
        assertEquals("full", gmm.getCovarianceType());
        assertArrayEquals(new double[]{0.6, 0.4}, gmm.getWeights(), 1e-9);
        assertEquals(5.93, gmm.getMeans()[0][0], 1e-9);
        assertEquals(6.15, gmm.getMeans()[1][0], 1e-9);
    }
    
    @Test
    void testValidGMMParsing_TrimodalDistribution() {
        String json = "{\"n_components\":3,\"dimensions\":1,\"covariance_type\":\"full\"," +
                     "\"weights\":[0.5,0.3,0.2]," +
                     "\"means\":[[5.78],[5.45],[6.20]]," +
                     "\"covariances\":[[[0.25]],[[0.36]],[[0.49]]]}";
        
        GMMValue gmm = (GMMValue) GMMDatatype.INSTANCE.parse(json);
        
        assertNotNull(gmm);
        assertEquals(3, gmm.getNComponents());
        assertArrayEquals(new double[]{0.5, 0.3, 0.2}, gmm.getWeights(), 1e-9);
    }
    
    @Test
    void testValidGMMParsing_TwoDimensional() {
        String json = "{\"n_components\":2,\"dimensions\":2,\"covariance_type\":\"full\"," +
                     "\"weights\":[0.7,0.3]," +
                     "\"means\":[[1.0,2.0],[3.0,4.0]]," +
                     "\"covariances\":[[[1.0,0.0],[0.0,1.0]],[[2.0,0.5],[0.5,2.0]]]}";
        
        GMMValue gmm = (GMMValue) GMMDatatype.INSTANCE.parse(json);
        
        assertNotNull(gmm);
        assertEquals(2, gmm.getNComponents());
        assertEquals(2, gmm.getDimensions());
        assertArrayEquals(new double[]{1.0, 2.0}, gmm.getMeans()[0], 1e-9);
        assertArrayEquals(new double[]{3.0, 4.0}, gmm.getMeans()[1], 1e-9);
    }
    
    @Test
    void testInvalidJSON_WrongFieldOrder() {
        // d and K are swapped
        String json = "{\"dimensions\":1,\"n_components\":1,\"covariance_type\":\"full\"," +
                     "\"weights\":[1.0],\"means\":[[6.02]],\"covariances\":[[[0.16]]]}";
        
        assertThrows(DatatypeFormatException.class, () -> {
            GMMDatatype.INSTANCE.parse(json);
        });
    }
    
    @Test
    void testInvalidJSON_MissingField() {
        // Missing covariance_type
        String json = "{\"n_components\":1,\"dimensions\":1," +
                     "\"weights\":[1.0],\"means\":[[6.02]],\"covariances\":[[[0.16]]]}";
        
        assertThrows(DatatypeFormatException.class, () -> {
            GMMDatatype.INSTANCE.parse(json);
        });
    }
    
    @Test
    void testInvalidJSON_ExtraField() {
        String json = "{\"n_components\":1,\"dimensions\":1,\"covariance_type\":\"full\"," +
                     "\"weights\":[1.0],\"means\":[[6.02]],\"covariances\":[[[0.16]]]," +
                     "\"extra\":123}";
        
        assertThrows(DatatypeFormatException.class, () -> {
            GMMDatatype.INSTANCE.parse(json);
        });
    }
    
    @Test
    void testInvalidJSON_WeightsNotSumToOne() {
        String json = "{\"n_components\":2,\"dimensions\":1,\"covariance_type\":\"full\"," +
                     "\"weights\":[0.6,0.5]," +  // Sum = 1.1, not 1.0
                     "\"means\":[[5.93],[6.15]]," +
                     "\"covariances\":[[[0.04]],[[0.09]]]}";
        
        assertThrows(DatatypeFormatException.class, () -> {
            GMMDatatype.INSTANCE.parse(json);
        });
    }
    
    @Test
    void testInvalidJSON_NegativeWeight() {
        String json = "{\"n_components\":2,\"dimensions\":1,\"covariance_type\":\"full\"," +
                     "\"weights\":[-0.2,1.2]," +
                     "\"means\":[[5.93],[6.15]]," +
                     "\"covariances\":[[[0.04]],[[0.09]]]}";
        
        assertThrows(DatatypeFormatException.class, () -> {
            GMMDatatype.INSTANCE.parse(json);
        });
    }
    
    @Test
    void testInvalidJSON_DimensionMismatch_Means() {
        String json = "{\"n_components\":2,\"dimensions\":2,\"covariance_type\":\"full\"," +
                     "\"weights\":[0.6,0.4]," +
                     "\"means\":[[5.93],[6.15]]," +  // Should be 2D, but only 1D
                     "\"covariances\":[[[0.04,0.0],[0.0,0.04]],[[0.09,0.0],[0.0,0.09]]]}";
        
        assertThrows(DatatypeFormatException.class, () -> {
            GMMDatatype.INSTANCE.parse(json);
        });
    }
    
    @Test
    void testInvalidJSON_DimensionMismatch_Covariances() {
        String json = "{\"n_components\":2,\"dimensions\":2,\"covariance_type\":\"full\"," +
                     "\"weights\":[0.6,0.4]," +
                     "\"means\":[[5.93,1.0],[6.15,2.0]]," +
                     "\"covariances\":[[[0.04]],[[0.09]]]}";  // Should be 2×2, but only 1×1
        
        assertThrows(DatatypeFormatException.class, () -> {
            GMMDatatype.INSTANCE.parse(json);
        });
    }
    
    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "not json",
        "{}",
        "[]",
        "null",
        "{\"n_components\":\"not_a_number\",\"dimensions\":1,\"covariance_type\":\"full\",\"weights\":[1.0],\"means\":[[6.02]],\"covariances\":[[[0.16]]]}"
    })
    void testInvalidJSONFormats(String invalidJson) {
        assertThrows(DatatypeFormatException.class, () -> {
            GMMDatatype.INSTANCE.parse(invalidJson);
        });
    }
    
    @Test
    void testIsValid() {
        String validJson = "{\"n_components\":1,\"dimensions\":1,\"covariance_type\":\"full\"," +
                          "\"weights\":[1.0],\"means\":[[6.02]],\"covariances\":[[[0.16]]]}";
        
        assertTrue(GMMDatatype.INSTANCE.isValid(validJson));
        
        String invalidJson = "{\"n_components\":1}";
        assertFalse(GMMDatatype.INSTANCE.isValid(invalidJson));
    }
    
    @Test
    void testUnparse() {
        String originalJson = "{\"n_components\":1,\"dimensions\":1,\"covariance_type\":\"full\"," +
                             "\"weights\":[1.0],\"means\":[[6.02]],\"covariances\":[[[0.16]]]}";
        
        GMMValue gmm = (GMMValue) GMMDatatype.INSTANCE.parse(originalJson);
        String unparsed = GMMDatatype.INSTANCE.unparse(gmm);
        
        assertNotNull(unparsed);
        assertTrue(unparsed.contains("\"n_components\":1"));
        assertTrue(unparsed.contains("\"dimensions\":1"));
        assertTrue(unparsed.contains("\"covariance_type\":\"full\""));
        assertTrue(unparsed.contains("\"weights\":[1.0]"));
        
        // Verify round-trip
        GMMValue reparsed = (GMMValue) GMMDatatype.INSTANCE.parse(unparsed);
        assertEquals(gmm, reparsed);
    }
    
    @Test
    void testGMMValueEquality() {
        String json1 = "{\"n_components\":2,\"dimensions\":1,\"covariance_type\":\"full\"," +
                      "\"weights\":[0.6,0.4],\"means\":[[5.93],[6.15]]," +
                      "\"covariances\":[[[0.04]],[[0.09]]]}";
        
        String json2 = "{\"n_components\":2,\"dimensions\":1,\"covariance_type\":\"full\"," +
                      "\"weights\":[0.6,0.4],\"means\":[[5.93],[6.15]]," +
                      "\"covariances\":[[[0.04]],[[0.09]]]}";
        
        GMMValue gmm1 = (GMMValue) GMMDatatype.INSTANCE.parse(json1);
        GMMValue gmm2 = (GMMValue) GMMDatatype.INSTANCE.parse(json2);
        
        assertEquals(gmm1, gmm2);
        assertEquals(gmm1.hashCode(), gmm2.hashCode());
    }
    
    @Test
    void testGMMValueToString() {
        String json = "{\"n_components\":2,\"dimensions\":1,\"covariance_type\":\"full\"," +
                     "\"weights\":[0.6,0.4],\"means\":[[5.93],[6.15]]," +
                     "\"covariances\":[[[0.04]],[[0.09]]]}";
        
        GMMValue gmm = (GMMValue) GMMDatatype.INSTANCE.parse(json);
        String str = gmm.toString();
        
        assertTrue(str.contains("n_components=2"));
        assertTrue(str.contains("dimensions=1"));
        assertTrue(str.contains("covariance_type='full'"));
    }
    
    @Test
    void testDiagonalCovariance_Valid() {
        String json = "{\"n_components\":2,\"dimensions\":3,\"covariance_type\":\"diag\"," +
                     "\"weights\":[0.5,0.5]," +
                     "\"means\":[[1.0,2.0,3.0],[4.0,5.0,6.0]]," +
                     "\"covariances\":[[0.1,0.2,0.3],[0.4,0.5,0.6]]}";
        
        GMMValue gmm = (GMMValue) GMMDatatype.INSTANCE.parse(json);
        
        assertNotNull(gmm);
        assertEquals(2, gmm.getNComponents());
        assertEquals(3, gmm.getDimensions());
        assertEquals("diag", gmm.getCovarianceType());
    }
    
    @Test
    void testDiagonalCovariance_NegativeVariance() {
        String json = "{\"n_components\":1,\"dimensions\":2,\"covariance_type\":\"diag\"," +
                     "\"weights\":[1.0]," +
                     "\"means\":[[1.0,2.0]]," +
                     "\"covariances\":[[0.5,-0.1]]}";  // Negative variance
        
        assertThrows(DatatypeFormatException.class, () -> {
            GMMDatatype.INSTANCE.parse(json);
        });
    }
    
    @Test
    void testDiagonalCovariance_ZeroVariance() {
        String json = "{\"n_components\":1,\"dimensions\":2,\"covariance_type\":\"diag\"," +
                     "\"weights\":[1.0]," +
                     "\"means\":[[1.0,2.0]]," +
                     "\"covariances\":[[0.5,0.0]]}";  // Zero variance (not allowed)
        
        assertThrows(DatatypeFormatException.class, () -> {
            GMMDatatype.INSTANCE.parse(json);
        });
    }
    
    @Test
    void testSphericalCovariance_Valid() {
        String json = "{\"n_components\":3,\"dimensions\":1,\"covariance_type\":\"spherical\"," +
                     "\"weights\":[0.4,0.3,0.3]," +
                     "\"means\":[[1.0],[2.0],[3.0]]," +
                     "\"covariances\":[0.5,1.0,0.25]}";
        
        GMMValue gmm = (GMMValue) GMMDatatype.INSTANCE.parse(json);
        
        assertNotNull(gmm);
        assertEquals(3, gmm.getNComponents());
        assertEquals(1, gmm.getDimensions());
        assertEquals("spherical", gmm.getCovarianceType());
    }
    
    @Test
    void testSphericalCovariance_NegativeVariance() {
        String json = "{\"K\":1,\"d\":2,\"covariance_type\":\"spherical\"," +
                     "\"weights\":[1.0]," +
                     "\"means\":[[1.0,2.0]]," +
                     "\"covariances\":[-0.5]}";
        
        assertThrows(DatatypeFormatException.class, () -> {
            GMMDatatype.INSTANCE.parse(json);
        });
    }
    
    @Test
    void testFullCovariance_NonSymmetric() {
        String json = "{\"K\":1,\"d\":2,\"covariance_type\":\"full\"," +
                     "\"weights\":[1.0]," +
                     "\"means\":[[0.0,0.0]]," +
                     "\"covariances\":[[[1.0,0.5],[0.3,1.0]]]}";  // Not symmetric
        
        assertThrows(DatatypeFormatException.class, () -> {
            GMMDatatype.INSTANCE.parse(json);
        });
    }
    
    @Test
    void testFullCovariance_NotPositiveDefinite() {
        String json = "{\"K\":1,\"d\":2,\"covariance_type\":\"full\"," +
                     "\"weights\":[1.0]," +
                     "\"means\":[[0.0,0.0]]," +
                     "\"covariances\":[[[1.0,2.0],[2.0,1.0]]]}";  // Not PSD (negative eigenvalue)
        
        assertThrows(DatatypeFormatException.class, () -> {
            GMMDatatype.INSTANCE.parse(json);
        });
    }
    
    @Test
    void testFullCovariance_PositiveSemiDefinite() {
        // Valid PSD matrix (identity matrix)
        String json = "{\"n_components\":1,\"dimensions\":2,\"covariance_type\":\"full\"," +
                     "\"weights\":[1.0]," +
                     "\"means\":[[0.0,0.0]]," +
                     "\"covariances\":[[[1.0,0.0],[0.0,1.0]]]}";
        
        GMMValue gmm = (GMMValue) GMMDatatype.INSTANCE.parse(json);
        assertNotNull(gmm);
    }
    
    @Test
    void testInvalidCovarianceType() {
        String json = "{\"n_components\":1,\"dimensions\":1,\"covariance_type\":\"invalid\"," +
                     "\"weights\":[1.0],\"means\":[[1.0]],\"covariances\":[[[0.5]]]}";
        
        assertThrows(DatatypeFormatException.class, () -> {
            GMMDatatype.INSTANCE.parse(json);
        });
    }
}
