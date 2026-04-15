package org.apache.jena.probsparql.datatypes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jena.datatypes.BaseDatatype;
import org.apache.jena.datatypes.DatatypeFormatException;

/**
 * Custom RDF datatype for Dirichlet distribution literals.
 *
 * <p>Lexical form is a JSON object:</p>
 * <pre>{"alphas":[2.5,1.0,3.0,0.5]}</pre>
 *
 * <ul>
 *   <li>alphas – array of strictly positive concentration parameters</li>
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

            if (!root.isObject())
                throw new DatatypeFormatException(lexicalForm, this,
                        "Dirichlet literal must be a JSON object");

            if (root.size() != 1 || !root.has("alphas"))
                throw new DatatypeFormatException(lexicalForm, this,
                        "Dirichlet literal must contain exactly one top-level field: alphas");

            JsonNode alphaNode = root.get("alphas");
            if (!alphaNode.isArray())
                throw new DatatypeFormatException(lexicalForm, this,
                        "Field 'alphas' must be an array");

            int k = alphaNode.size();
            if (k < 2)
                throw new DatatypeFormatException(lexicalForm, this,
                        "Field 'alphas' must contain at least 2 values, got: " + k);

            double[] alpha = new double[k];
            for (int i = 0; i < k; i++) {
                alpha[i] = alphaNode.get(i).asDouble();
                if (alpha[i] <= 0.0)
                    throw new DatatypeFormatException(lexicalForm, this,
                            "All alpha values must be positive, got alphas[" + i + "]=" + alpha[i]);
            }

            return new DirichletValue(alpha);
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
