package org.apache.jena.probsparql.datatypes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jena.datatypes.BaseDatatype;
import org.apache.jena.datatypes.DatatypeFormatException;

/**
 * Custom RDF datatype for Dirichlet distribution literals.
 *
 * <p>Lexical form is a JSON object:</p>
 * <pre>{"type":"dirichlet","k":4,"alpha":[2.5,1.0,3.0,0.5]}</pre>
 *
 * <ul>
 *   <li>type  – must be the string {@code "dirichlet"}</li>
 *   <li>k     – positive integer, the dimension of the simplex</li>
 *   <li>alpha – array of k positive concentration parameters</li>
 * </ul>
 *
 * <p>URI: {@code http://example.org/ontology/uncertainty#dirichletLiteral}</p>
 */
public class DirichletDatatype extends BaseDatatype {

    public static final String URI =
            "http://example.org/ontology/uncertainty#dirichletLiteral";

    public static final DirichletDatatype INSTANCE = new DirichletDatatype();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DirichletDatatype() {
        super(URI);
    }

    @Override
    public Object parse(String lexicalForm) throws DatatypeFormatException {
        if (lexicalForm == null || lexicalForm.isBlank())
            throw new DatatypeFormatException(lexicalForm, this,
                    "Lexical form cannot be null or empty");
        try {
            JsonNode root = MAPPER.readTree(lexicalForm);

            // Validate required fields
            if (!root.has("type") || !root.has("k") || !root.has("alpha"))
                throw new DatatypeFormatException(lexicalForm, this,
                        "Dirichlet literal must contain fields: type, k, alpha");

            String type = root.get("type").asText();
            if (!"dirichlet".equals(type))
                throw new DatatypeFormatException(lexicalForm, this,
                        "Field 'type' must be 'dirichlet', got: " + type);

            int k = root.get("k").asInt();
            if (k <= 0)
                throw new DatatypeFormatException(lexicalForm, this,
                        "Field 'k' must be a positive integer, got: " + k);

            JsonNode alphaNode = root.get("alpha");
            if (!alphaNode.isArray() || alphaNode.size() != k)
                throw new DatatypeFormatException(lexicalForm, this,
                        "Field 'alpha' must be an array of length k=" + k);

            double[] alpha = new double[k];
            for (int i = 0; i < k; i++) {
                alpha[i] = alphaNode.get(i).asDouble();
                if (alpha[i] <= 0.0)
                    throw new DatatypeFormatException(lexicalForm, this,
                            "All alpha values must be positive, got alpha[" + i + "]=" + alpha[i]);
            }

            return new DirichletValue(k, alpha);
        } catch (DatatypeFormatException e) {
            throw e;
        } catch (Exception e) {
            throw new DatatypeFormatException(lexicalForm, this,
                    "Failed to parse Dirichlet literal: " + e.getMessage());
        }
    }

    @Override
    public String unparse(Object value) {
        if (value instanceof DirichletValue d) {
            return d.toJSON();
        }
        return value.toString();
    }

    @Override
    public Class<?> getJavaClass() {
        return DirichletValue.class;
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
}
