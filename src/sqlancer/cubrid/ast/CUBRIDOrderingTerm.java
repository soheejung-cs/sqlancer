package sqlancer.cubrid.ast;

import sqlancer.common.ast.newast.NewOrderingTerm;

public class CUBRIDOrderingTerm extends NewOrderingTerm<CUBRIDExpression> implements CUBRIDExpression {

    public CUBRIDOrderingTerm(CUBRIDExpression expr, Ordering ordering) {
        super(expr, ordering);
    }

}
