package org.apache.jena.probsparql.datatypes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jena.datatypes.BaseDatatype;
import org.apache.jena.datatypes.DatatypeFormatException;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Custom RDF datatype for histogram literals.
 *
 * <p>The preferred lexical form is the multidimensional schema:</p>
 * <pre>
 * {"dimensions":2,"edges":[[0.0,1.0,2.0],[10.0,20.0,30.0]],"weights":[0.1,0.2,0.3,0.4]}
 * </pre>
 *
 * <p>For backwards compatibility, legacy 1-D literals of the form
 * {@code {"bins":[...],"weights":[...]}} are still accepted. Unparsing always
 * emits the preferred multidimensional schema.</p>
 */
public class HistogramDatatype extends BaseDatatype {

    public static final String URI =
            "http://example.org/ontology/uncertainty#histLiteral";

    public static final HistogramDatatype INSTANCE = new HistogramDatatype();

    private static final String[] REQUIRED_FIELDS_NEW = {"dimensions", "edges", "weights"};
    private static final String[] REQUIRED_FIELDS_OLD = {"bins", "weights"};

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HistogramDatatype() {
        super(URI);
    }

    @Override
    public Object parse(String lexicalForm) throws DatatypeFormatException {
        if (lexicalForm == null || lexicalForm.isBlank()) {
            throw new DatatypeFormatException(lexicalForm, this,
                "Lexical form cannot be null or empty");
        }

        try {
            JsonNode root = MAPPER.readTree(lexicalForm);
            if (!root.isObject()) {
                throw new DatatypeFormatException(lexicalForm, this,
                    "Root must be a JSON object");
            }

            if (root.has("dimensions") || root.has("edges")) {
                validateFieldOrder(root, lexicalForm, REQUIRED_FIELDS_NEW);
                return parseNewSchema(root, lexicalForm);
            }

            validateFieldOrder(root, lexicalForm, REQUIRED_FIELDS_OLD);
            return parseLegacySchema(root, lexicalForm);
        } catch (DatatypeFormatException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new DatatypeFormatException(lexicalForm, this,
                "Validation error: " + e.getMessage());
        } catch (Exception e) {
            throw new DatatypeFormatException(lexicalForm, this,
                "JSON parsing error: " + e.getMessage());
        }
    }

    @Override
    public String unparse(Object value) {
        if (value instanceof HistogramValue histogram) {
            return histogram.toString();
        }
        throw new IllegalArgumentException(
            "Cannot unparse value of type " + value.getClass().getName());
    }

    @Override
    public boolean isValid(String lexicalForm) {
        try {
            parse(lexicalForm);
            return true;
        } catch (DatatypeFormatException e) {
            return false;
        }
    }

    @Override
    public Class<?> getJavaClass() {
        return HistogramValue.class;
    }

    private HistogramValue parseNewSchema(JsonNode root, String lexicalForm)
        throws DatatypeFormatException {
        JsonNode dimensionsNode = root.get("dimensions");
        if (dimensionsNode == null || !dimensionsNode.isInt()) {
            throw new DatatypeFormatException(lexicalForm, this,
                "Field 'dimensions' must be an integer");
        }
        int dimensions = dimensionsNode.asInt();
        if (dimensions <= 0) {
            throw new DatatypeFormatException(lexicalForm, this,
                "Field 'dimensions' must be positive");
        }

        JsonNode edgesNode = root.get("edges");
        if (edgesNode == null || !edgesNode.isArray()) {
            throw new DatatypeFormatException(lexicalForm, this,
                "Field 'edges' must be an array");
        }
        if (edgesNode.size() != dimensions) {
            throw new DatatypeFormatException(lexicalForm, this,
                "Field 'edges' must have length equal to dimensions=" + dimensions);
        }

        double[][] edges = new double[dimensions][];
        int totalCells = 1;
        for (int dim = 0; dim < dimensions; dim++) {
            JsonNode dimEdgesNode = edgesNode.get(dim);
            if (dimEdgesNode == null || !dimEdgesNode.isArray()) {
                throw new DatatypeFormatException(lexicalForm, this,
                    "edges[" + dim + "] must be an array");
            }
            if (dimEdgesNode.size() < 2) {
                throw new DatatypeFormatException(lexicalForm, this,
                    "edges[" + dim + "] must have length at least 2");
            }

            edges[dim] = new double[dimEdgesNode.size()];
            for (int i = 0; i < dimEdgesNode.size(); i++) {
                JsonNode edge = dimEdgesNode.get(i);
                if (!edge.isNumber()) {
                    throw new DatatypeFormatException(lexicalForm, this,
                        "edges[" + dim + "][" + i + "] must be numeric");
                }
                edges[dim][i] = edge.asDouble();
                if (i > 0 && !(edges[dim][i] > edges[dim][i - 1])) {
                    throw new DatatypeFormatException(lexicalForm, this,
                        "edges[" + dim + "] must be strictly increasing");
                }
            }
            totalCells *= (edges[dim].length - 1);
        }

        JsonNode weightsNode = root.get("weights");
        double[] weights = parseWeights(weightsNode, totalCells, lexicalForm);

        return new HistogramValue(dimensions, edges, weights);
    }

    private HistogramValue parseLegacySchema(JsonNode root, String lexicalForm)
        throws DatatypeFormatException {
        JsonNode binsNode = root.get("bins");
        if (binsNode == null || !binsNode.isArray()) {
            throw new DatatypeFormatException(lexicalForm, this,
                "Field 'bins' must be an array");
        }
        if (binsNode.size() < 2) {
            throw new DatatypeFormatException(lexicalForm, this,
                "Field 'bins' must have length at least 2");
        }

        double[] bins = new double[binsNode.size()];
        for (int i = 0; i < bins.length; i++) {
            JsonNode edge = binsNode.get(i);
            if (!edge.isNumber()) {
                throw new DatatypeFormatException(lexicalForm, this,
                    "bins[" + i + "] must be numeric");
            }
            bins[i] = edge.asDouble();
            if (i > 0 && !(bins[i] > bins[i - 1])) {
                throw new DatatypeFormatException(lexicalForm, this,
                    "bins must be strictly increasing");
            }
        }

        JsonNode weightsNode = root.get("weights");
        double[] weights = parseWeights(weightsNode, bins.length - 1, lexicalForm);
        return new HistogramValue(bins, weights);
    }

    private double[] parseWeights(JsonNode weightsNode, int expectedLength, String lexicalForm)
        throws DatatypeFormatException {
        if (weightsNode == null || !weightsNode.isArray()) {
            throw new DatatypeFormatException(lexicalForm, this,
                "Field 'weights' must be an array");
        }
        if (weightsNode.size() != expectedLength) {
            throw new DatatypeFormatException(lexicalForm, this,
                "weights length (" + weightsNode.size() + ") must equal expected cell count (" + expectedLength + ")");
        }

        double[] weights = new double[expectedLength];
        double sum = 0.0;
        for (int i = 0; i < expectedLength; i++) {
            JsonNode elem = weightsNode.get(i);
            if (!elem.isNumber()) {
                throw new DatatypeFormatException(lexicalForm, this,
                    "weights[" + i + "] must be numeric");
            }
            weights[i] = elem.asDouble();
            if (weights[i] < 0.0) {
                throw new DatatypeFormatException(lexicalForm, this,
                    "weights[" + i + "] must be non-negative");
            }
            sum += weights[i];
        }
        if (Math.abs(sum - 1.0) > 1e-6) {
            throw new DatatypeFormatException(lexicalForm, this,
                "weights must sum to 1.0 within tolerance; got: " + sum);
        }
        return weights;
    }

    private void validateFieldOrder(JsonNode root, String lexicalForm, String[] requiredFields)
        throws DatatypeFormatException {
        List<String> actual = new ArrayList<>();
        Iterator<String> it = root.fieldNames();
        while (it.hasNext()) actual.add(it.next());

        if (actual.size() != requiredFields.length) {
            throw new DatatypeFormatException(lexicalForm, this,
                "Must have exactly " + requiredFields.length + " fields, found: " + actual.size());
        }

        for (int i = 0; i < requiredFields.length; i++) {
            if (!actual.get(i).equals(requiredFields[i])) {
                throw new DatatypeFormatException(lexicalForm, this,
                    "Field at position " + i + " must be '" + requiredFields[i]
                        + "', found: '" + actual.get(i) + "'");
            }
        }
    }
}
