package sqlancer.cubrid.ast;

// CASE WHEN condition THEN thenExpr ELSE elseExpr END
public class CUBRIDSearchedCase implements CUBRIDExpression {

    private final CUBRIDExpression condition;
    private final CUBRIDExpression thenExpr;
    private final CUBRIDExpression elseExpr;

    public CUBRIDSearchedCase(CUBRIDExpression condition, CUBRIDExpression thenExpr, CUBRIDExpression elseExpr) {
        this.condition = condition;
        this.thenExpr = thenExpr;
        this.elseExpr = elseExpr;
    }

    public CUBRIDExpression getCondition() {
        return condition;
    }

    public CUBRIDExpression getThenExpr() {
        return thenExpr;
    }

    public CUBRIDExpression getElseExpr() {
        return elseExpr;
    }

}
