package sqlancer.cubrid.ast;

// [NOT] EXISTS (subquery)
public class CUBRIDExistsOperation implements CUBRIDExpression {

    private final CUBRIDSelect subquery;
    private final boolean negated;

    public CUBRIDExistsOperation(CUBRIDSelect subquery, boolean negated) {
        this.subquery = subquery;
        this.negated = negated;
    }

    public CUBRIDSelect getSubquery() {
        return subquery;
    }

    public boolean isNegated() {
        return negated;
    }

}
