package org.apache.jena.probsparql.datatypes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jena.datatypes.BaseDatatype;
import org.apache.jena.datatypes.DatatypeFormatException;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Custom RDF datatype for 1-D histogram literals.
 *
 * <p>Lexical form is a JSON object with exactly two fields in this order:</p>
 * <pre>{"bins":[0.0,10.0,20.0,30.0],"weights":[0.2,0.5,0.3]}</pre>
 *
 * <ul>
 *   <li>bins    – numeric array of length N+1 with strictly increasing boundaries</li>
 *   <li>weights – numeric array of length N with non-negative values summing to 1</li>
 * </ul>
 *
 * <p>Singleton access via {@link #INSTANCE}.</p>
 */
public class HistogramDatatype extends BaseDatatype {

    public static final String URI =
            "http://example.org/ontology/uncertainty#histLiteral";

    public static final HistogramDatatype INSTANCE = new HistogramDatatype();

    private static final String[] REQUIRED_FIELDS = {"bins", "weights"};

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HistogramDatatype() {
        super(URI);
    }

    // -----------------------------------------------------------------------
    // BaseDatatype overrides
    // -----------------------------------------------------------------------

    @Override
    public Object parse(String lexicalForm) throws DatatypeFormatException {
        if (lexicalForm == null || lexicalForm.isBlank())
            throw new DatatypeFormatException(lexicalForm, this,
                    "Lexical form cannot be null or empty");

        try {
            JsonNode root = MAPPER.readTree(lexicalForm);
            if (!root.isObject())
                throw new DatatypeFormatException(lexicalForm, this,
                        "Root must be a JSON object");

            validateFieldOrder(root, lexicalForm);

            JsonNode binsNode = root.get("bins");
            if (binsNode == null || !binsNode.isArray())
                throw new DatatypeFormatException(lexicalForm, this,
                        "Field 'bins' must be an array");

            JsonNode weightsNode = root.get("weights");
            if (weightsNode == null || !weightsNode.isArray())
                throw new DatatypeFormatException(lexicalForm, this,
                        "Field 'weights' must be an array");

            if (binsNode.size() < 2)
                throw new DatatypeFormatException(lexicalForm, this,
                        "Field 'bins' must have length at least 2");

            int n = binsNode.size() - 1;
            if (weightsNode.size() != n)
                throw new DatatypeFormatException(lexicalForm, this,
                        "weights length (" + weightsNode.size() + ") must equal bins.length - 1 (" + n + ")");

            double[] bins = new double[n + 1];
            for (int i = 0; i < bins.length; i++) {
                JsonNode elem = binsNode.get(i);
                if (!elem.isNumber())
                    throw new DatatypeFormatException(lexicalForm, this,
                            "bins[" + i + "] must be numeric");
                bins[i] = elem.asDouble();
                if (i > 0 && !(bins[i] > bins[i - 1])) {
                    throw new DatatypeFormatException(lexicalForm, this,
                            "bins must be strictly increasing; bins[" + (i - 1) + "]="
                                    + bins[i - 1] + ", bins[" + i + "]=" + bins[i]);
                }
            }

            double[] weights = new double[n];
            double sum = 0.0;
            for (int i = 0; i < n; i++) {
                JsonNode elem = weightsNode.get(i);
                if (!elem.isNumber())
                    throw new DatatypeFormatException(lexicalForm, this,
                            "weights[" + i + "] must be numeric");
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

            return new HistogramValue(bins, weights);

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
        if (value instanceof HistogramValue)
            return value.toString();
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

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void validateFieldOrder(JsonNode root, String lexicalForm)
            throws DatatypeFormatException {
        List<String> actual = new ArrayList<>();
        Iterator<String> it = root.fieldNames();
        while (it.hasNext()) actual.add(it.next());

        if (actual.size() != REQUIRED_FIELDS.length)
            throw new DatatypeFormatException(lexicalForm, this,
                    "Must have exactly " + REQUIRED_FIELDS.length
                            + " fields, found: " + actual.size());

        for (int i = 0; i < REQUIRED_FIELDS.length; i++) {
            if (!actual.get(i).equals(REQUIRED_FIELDS[i]))
                throw new DatatypeFormatException(lexicalForm, this,
                        "Field at position " + i + " must be '"
                                + REQUIRED_FIELDS[i] + "', found: '" + actual.get(i) + "'");
        }
    }
}
