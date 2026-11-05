package sqlancer.cubrid.ast;

// left [NOT] IN (subquery)
public class CUBRIDInSubqueryOperation implements CUBRIDExpression {

    private final CUBRIDExpression left;
    private final CUBRIDSelect subquery;
    private final boolean negated;

    public CUBRIDInSubqueryOperation(CUBRIDExpression left, CUBRIDSelect subquery, boolean negated) {
        this.left = left;
        this.subquery = subquery;
        this.negated = negated;
    }

    public CUBRIDExpression getLeft() {
        return left;
    }

    public CUBRIDSelect getSubquery() {
        return subquery;
    }

    public boolean isNegated() {
        return negated;
    }

}
