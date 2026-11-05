package sqlancer.cubrid.ast;

import sqlancer.common.ast.BinaryOperatorNode.Operator;

// left op ALL | ANY | SOME (subquery)
public class CUBRIDQuantifiedComparison implements CUBRIDExpression {

    public enum Quantifier {
        ALL, ANY, SOME
    }

    private final CUBRIDExpression left;
    private final Operator op;
    private final Quantifier quantifier;
    private final CUBRIDSelect subquery;

    public CUBRIDQuantifiedComparison(CUBRIDExpression left, Operator op, Quantifier quantifier,
            CUBRIDSelect subquery) {
        this.left = left;
        this.op = op;
        this.quantifier = quantifier;
        this.subquery = subquery;
    }

    public CUBRIDExpression getLeft() {
        return left;
    }

    public Operator getOp() {
        return op;
    }

    public Quantifier getQuantifier() {
        return quantifier;
    }

    public CUBRIDSelect getSubquery() {
        return subquery;
    }

}
