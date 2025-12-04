package org.apache.jena.sparql.algebra.op;

import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpVisitor;
import org.apache.jena.sparql.util.NodeIsomorphismMap;
import org.apache.jena.atlas.io.IndentedWriter;
import org.apache.jena.atlas.io.IndentedLineBuffer;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.sparql.serializer.SerializationContext;

/**
 * Base class for custom probabilistic algebra operators.
 * Provides common implementation of the Op interface for OpFuseJoin and OpSimilarityJoin.
 */
public abstract class OpBase implements Op {
    
    @Override
    public abstract String getName();
    
    @Override
    public abstract void visit(OpVisitor opVisitor);
    
    @Override
    public abstract boolean equalTo(Op other, NodeIsomorphismMap labelMap);
    
    @Override
    public abstract int hashCode();
    
    /**
     * Output the operator structure for debugging (simple version).
     * Required by Printable interface.
     */
    @Override
    public void output(IndentedWriter out) {
        out.print("(" + getName() + ")");
    }
    
    /**
     * Output the operator structure for debugging (with serialization context).
     * Subclasses should override this to provide detailed output.
     */
    @Override
    public void output(IndentedWriter out, SerializationContext sCxt) {
        output(out);
    }
    
    /**
     * Implementation of PrintSerializable.toString(PrefixMapping).
     */
    @Override
    public String toString(PrefixMapping pmap) {
        IndentedLineBuffer buff = new IndentedLineBuffer();
        SerializationContext sCxt = new SerializationContext(pmap);
        output(buff, sCxt);
        return buff.asString();
    }
}

