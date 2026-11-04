package sqlancer.cubrid.ast;

import sqlancer.common.ast.BinaryOperatorNode.Operator;
import sqlancer.common.ast.newast.NewUnaryPostfixOperatorNode;

public class CUBRIDUnaryPostfixOperation extends NewUnaryPostfixOperatorNode<CUBRIDExpression>
        implements CUBRIDExpression {

    public CUBRIDUnaryPostfixOperation(CUBRIDExpression expr, Operator op) {
        super(expr, op);
    }

}
