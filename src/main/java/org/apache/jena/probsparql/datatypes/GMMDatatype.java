package org.apache.jena.probsparql.datatypes;

import org.apache.jena.datatypes.BaseDatatype;
import org.apache.jena.datatypes.DatatypeFormatException;
import org.apache.jena.graph.impl.LiteralLabel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Custom RDF datatype for Gaussian Mixture Model (GMM) literals.
 * 
 * A well-formed lexical form MUST be a JSON object with exactly six top-level fields,
 * in this order and with no additional fields:
 * "n_components", "dimensions", "covariance_type", "weights", "means", and "covariances".
 * 
 * @author ProbSPARQL Team
 * @version 1.0.0
 */
public class GMMDatatype extends BaseDatatype {
    
    private static final Logger logger = LoggerFactory.getLogger(GMMDatatype.class);
    
    /** URI of the GMM datatype */
    public static final String URI = "http://example.org/ontology/uncertainty#gmmLiteral";
    
    /** Singleton instance */
    public static final GMMDatatype INSTANCE = new GMMDatatype();
    
    /** Expected field names in required order */
    private static final String[] REQUIRED_FIELDS = {"n_components", "dimensions", "covariance_type", "weights", "means", "covariances"};
    
    /** JSON parser */
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * Private constructor for singleton pattern.
     */
    private GMMDatatype() {
        super(URI);
    }
    
    /**
     * Parse a JSON string into a GMMValue object.
     * 
     * @param lexicalForm JSON string representation
     * @return parsed GMMValue
     * @throws DatatypeFormatException if parsing fails
     */
    @Override
    public Object parse(String lexicalForm) throws DatatypeFormatException {
        if (lexicalForm == null || lexicalForm.trim().isEmpty()) {
            throw new DatatypeFormatException(lexicalForm, this, "Lexical form cannot be null or empty");
        }
        
        try {
            JsonNode root = objectMapper.readTree(lexicalForm);
            
            if (!root.isObject()) {
                throw new DatatypeFormatException(lexicalForm, this, "Root must be a JSON object");
            }
            
            // Validate that fields appear in exact order with no extras
            validateFieldOrder(root, lexicalForm);
            
            // Extract K
            JsonNode kNode = root.get("n_components");
            if (kNode == null || !kNode.isInt()) {
                throw new DatatypeFormatException(lexicalForm, this, "Field 'n_components' must be an integer");
            }
            int K = kNode.asInt();
            
            // Extract d
            JsonNode dNode = root.get("dimensions");
            if (dNode == null || !dNode.isInt()) {
                throw new DatatypeFormatException(lexicalForm, this, "Field 'dimensions' must be an integer");
            }
            int d = dNode.asInt();
            
            // Extract covariance_type
            JsonNode covTypeNode = root.get("covariance_type");
            if (covTypeNode == null || !covTypeNode.isTextual()) {
                throw new DatatypeFormatException(lexicalForm, this, "Field 'covariance_type' must be a string");
            }
            String covarianceType = covTypeNode.asText();
            
            // Extract weights
            JsonNode weightsNode = root.get("weights");
            if (weightsNode == null || !weightsNode.isArray()) {
                throw new DatatypeFormatException(lexicalForm, this, "Field 'weights' must be an array");
            }
            double[] weights = parseDoubleArray(weightsNode, "weights", lexicalForm);
            
            // Extract means
            JsonNode meansNode = root.get("means");
            if (meansNode == null || !meansNode.isArray()) {
                throw new DatatypeFormatException(lexicalForm, this, "Field 'means' must be an array");
            }
            double[][] means = parseDouble2DArray(meansNode, "means", lexicalForm);
            
            // Extract covariances based on covariance_type
            JsonNode covariancesNode = root.get("covariances");
            if (covariancesNode == null || !covariancesNode.isArray()) {
                throw new DatatypeFormatException(lexicalForm, this, "Field 'covariances' must be an array");
            }
            double[][][] covariances = parseCovariancesByType(covariancesNode, K, d, covarianceType, lexicalForm);
            
            // Create and validate GMMValue
            return new GMMValue(K, d, covarianceType, weights, means, covariances);
            
        } catch (DatatypeFormatException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new DatatypeFormatException(lexicalForm, this, "Validation error: " + e.getMessage());
        } catch (Exception e) {
            throw new DatatypeFormatException(lexicalForm, this, "JSON parsing error: " + e.getMessage());
        }
    }

    /**
     * Validate that JSON object has exactly the required fields in the correct order.
     */
    private void validateFieldOrder(JsonNode root, String lexicalForm) throws DatatypeFormatException {
        List<String> actualFields = new ArrayList<>();
        Iterator<String> fieldNames = root.fieldNames();
        while (fieldNames.hasNext()) {
            actualFields.add(fieldNames.next());
        }
        
        // Check exact count
        if (actualFields.size() != REQUIRED_FIELDS.length) {
            throw new DatatypeFormatException(lexicalForm, this, 
                "Must have exactly " + REQUIRED_FIELDS.length + " fields, found: " + actualFields.size());
        }
        
        // Check order and names
        for (int i = 0; i < REQUIRED_FIELDS.length; i++) {
            if (!actualFields.get(i).equals(REQUIRED_FIELDS[i])) {
                throw new DatatypeFormatException(lexicalForm, this,
                    "Fields must be exactly " + String.join(", ", REQUIRED_FIELDS)
                    + " in order; found: " + actualFields);
            }
        }
    }
    
    /**
     * Parse a JSON array node into a double array.
     */
    private double[] parseDoubleArray(JsonNode arrayNode, String fieldName, String lexicalForm) throws DatatypeFormatException {
        if (!arrayNode.isArray()) {
            throw new DatatypeFormatException(lexicalForm, this, fieldName + " must be an array");
        }
        
        int size = arrayNode.size();
        double[] result = new double[size];
        
        for (int i = 0; i < size; i++) {
            JsonNode elem = arrayNode.get(i);
            if (!elem.isNumber()) {
                throw new DatatypeFormatException(lexicalForm, this, 
                    fieldName + "[" + i + "] must be a number");
            }
            result[i] = elem.asDouble();
        }
        
        return result;
    }
    
    /**
     * Parse a JSON 2D array node into a double[][] array.
     */
    private double[][] parseDouble2DArray(JsonNode arrayNode, String fieldName, String lexicalForm) throws DatatypeFormatException {
        if (!arrayNode.isArray()) {
            throw new DatatypeFormatException(lexicalForm, this, fieldName + " must be an array");
        }
        
        int size = arrayNode.size();
        double[][] result = new double[size][];
        
        for (int i = 0; i < size; i++) {
            JsonNode elem = arrayNode.get(i);
            result[i] = parseDoubleArray(elem, fieldName + "[" + i + "]", lexicalForm);
        }
        
        return result;
    }
    
    /**
     * Parse a JSON 3D array node into a double[][][] array.
     */
    private double[][][] parseDouble3DArray(JsonNode arrayNode, String fieldName, String lexicalForm) throws DatatypeFormatException {
        if (!arrayNode.isArray()) {
            throw new DatatypeFormatException(lexicalForm, this, fieldName + " must be an array");
        }
        
        int size = arrayNode.size();
        double[][][] result = new double[size][][];
        
        for (int i = 0; i < size; i++) {
            JsonNode elem = arrayNode.get(i);
            result[i] = parseDouble2DArray(elem, fieldName + "[" + i + "]", lexicalForm);
        }
        
        return result;
    }
    
    /**
     * Parse covariances based on covariance_type.
     */
    private double[][][] parseCovariancesByType(JsonNode arrayNode, int K, int d, String covarianceType, 
                                                 String lexicalForm) throws DatatypeFormatException {
        if (!arrayNode.isArray()) {
            throw new DatatypeFormatException(lexicalForm, this, "covariances must be an array");
        }
        
        if (arrayNode.size() != K) {
            throw new DatatypeFormatException(lexicalForm, this, 
                "covariances array must have length K=" + K + ", got: " + arrayNode.size());
        }
        
        switch (covarianceType.toLowerCase()) {
            case "full":
                // Full: K × d × d tensor
                return parseDouble3DArray(arrayNode, "covariances", lexicalForm);
                
            case "diag":
                // Diagonal: K arrays of length d (wrap in extra dimension)
                double[][][] diagResult = new double[K][1][];
                for (int i = 0; i < K; i++) {
                    JsonNode elem = arrayNode.get(i);
                    diagResult[i][0] = parseDoubleArray(elem, "covariances[" + i + "]", lexicalForm);
                    if (diagResult[i][0].length != d) {
                        throw new DatatypeFormatException(lexicalForm, this,
                            "For 'diag' covariance: covariances[" + i + "] must have length d=" + d + 
                            ", got: " + diagResult[i][0].length);
                    }
                }
                return diagResult;
                
            case "spherical":
                // Spherical: K scalars (wrap in double arrays)
                double[][][] spherResult = new double[K][1][1];
                for (int i = 0; i < K; i++) {
                    JsonNode elem = arrayNode.get(i);
                    if (!elem.isNumber()) {
                        throw new DatatypeFormatException(lexicalForm, this,
                            "For 'spherical' covariance: covariances[" + i + "] must be a number");
                    }
                    spherResult[i][0][0] = elem.asDouble();
                }
                return spherResult;
                
            default:
                throw new DatatypeFormatException(lexicalForm, this,
                    "covariance_type must be 'full', 'diag', or 'spherical', got: '" + covarianceType + "'");
        }
    }
    
    /**
     * Convert a GMMValue object back to its JSON string representation.
     * 
     * @param value GMMValue object
     * @return JSON string
     */
    @Override
    public String unparse(Object value) {
        if (value instanceof GMMValue) {
            return ((GMMValue) value).toJSON();
        }
        return value.toString();
    }
    
    /**
     * Check if a lexical form is valid for this datatype.
     * 
     * @param lexicalForm string to validate
     * @return true if valid, false otherwise
     */
    @Override
    public boolean isValid(String lexicalForm) {
        try {
            parse(lexicalForm);
            return true;
        } catch (Exception e) {
            logger.debug("Invalid GMM literal: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Check if a literal label is a valid value for this datatype.
     * 
     * @param lit literal label to check
     * @return true if valid, false otherwise
     */
    @Override
    public boolean isValidLiteral(LiteralLabel lit) {
        return isValid(lit.getLexicalForm());
    }
    
    @Override
    public String toString() {
        return "GMMDatatype[" + URI + "]";
    }
}
