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
 * <p>Lexical form is a JSON object with exactly four fields in this order:</p>
 * <pre>{"B":50,"min":8.0,"max":12.0,"counts":[3,5,8,...]}</pre>
 *
 * <ul>
 *   <li>B      – integer, number of bins (&gt; 0)</li>
 *   <li>min    – number, left edge of the histogram</li>
 *   <li>max    – number, right edge of the histogram (must be > min)</li>
 *   <li>counts – integer array of length B with non-negative values</li>
 * </ul>
 *
 * <p>Singleton access via {@link #INSTANCE}.</p>
 */
public class HistogramDatatype extends BaseDatatype {

    public static final String URI =
            "http://example.org/ontology/uncertainty#histLiteral";

    public static final HistogramDatatype INSTANCE = new HistogramDatatype();

    private static final String[] REQUIRED_FIELDS = {"B", "min", "max", "counts"};

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

            // B
            JsonNode bNode = root.get("B");
            if (bNode == null || !bNode.isInt())
                throw new DatatypeFormatException(lexicalForm, this,
                        "Field 'B' must be an integer");
            int B = bNode.asInt();

            // min
            JsonNode minNode = root.get("min");
            if (minNode == null || !minNode.isNumber())
                throw new DatatypeFormatException(lexicalForm, this,
                        "Field 'min' must be a number");
            double min = minNode.asDouble();

            // max
            JsonNode maxNode = root.get("max");
            if (maxNode == null || !maxNode.isNumber())
                throw new DatatypeFormatException(lexicalForm, this,
                        "Field 'max' must be a number");
            double max = maxNode.asDouble();

            // counts
            JsonNode countsNode = root.get("counts");
            if (countsNode == null || !countsNode.isArray())
                throw new DatatypeFormatException(lexicalForm, this,
                        "Field 'counts' must be an array");
            if (countsNode.size() != B)
                throw new DatatypeFormatException(lexicalForm, this,
                        "counts length (" + countsNode.size() + ") must equal B (" + B + ")");

            int[] counts = new int[B];
            for (int i = 0; i < B; i++) {
                JsonNode elem = countsNode.get(i);
                if (!elem.isInt() && !elem.isLong())
                    throw new DatatypeFormatException(lexicalForm, this,
                            "counts[" + i + "] must be an integer");
                counts[i] = elem.asInt();
            }

            return new HistogramValue(B, min, max, counts);

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
