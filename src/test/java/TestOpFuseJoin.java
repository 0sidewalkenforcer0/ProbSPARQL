import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.algebra.op.OpFuseJoin;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Var;

public class TestOpFuseJoin {
    public static void main(String[] args) {
        // Create simple BGPs for left and right tables
        BasicPattern leftBp = new BasicPattern();
        BasicPattern rightBp = new BasicPattern();
        OpBGP leftBgp = new OpBGP(leftBp);
        OpBGP rightBgp = new OpBGP(rightBp);
        
        // Create Var objects for the join parameters
        Var leftVar = Var.alloc("left");
        Var rightVar = Var.alloc("right");
        Var resultVar = Var.alloc("result");
        
        // Create OpFuseJoin with new relational semantics (left and right ops)
        OpFuseJoin fusejoin = new OpFuseJoin(leftBgp, rightBgp, leftVar, rightVar, 0.1, resultVar);
        
        System.out.println("OpFuseJoin toString:");
        System.out.println(fusejoin);
        System.out.println();
        System.out.println("OpFuseJoin getName: " + fusejoin.getName());
        System.out.println("OpFuseJoin getLeftOp: " + fusejoin.getLeftOp());
        System.out.println("OpFuseJoin getRightOp: " + fusejoin.getRightOp());
        System.out.println("OpFuseJoin getLeftVar: " + fusejoin.getLeftVar());
        System.out.println("OpFuseJoin getRightVar: " + fusejoin.getRightVar());
        System.out.println("OpFuseJoin getTolerance: " + fusejoin.getTolerance());
        System.out.println("OpFuseJoin getResultVar: " + fusejoin.getResultVar());
        System.out.println("OpFuseJoin isLegacyMode: " + fusejoin.isLegacyMode());
    }
}
