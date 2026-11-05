package sqlancer.cubrid.ast;

import sqlancer.common.ast.BinaryOperatorNode.Operator;
import sqlancer.common.ast.newast.NewUnaryPrefixOperatorNode;

public class CUBRIDUnaryPrefixOperation extends NewUnaryPrefixOperatorNode<CUBRIDExpression>
        implements CUBRIDExpression {

    public CUBRIDUnaryPrefixOperation(CUBRIDExpression expr, Operator op) {
        super(expr, op);
    }

    public Operator getOp() {
        return op;
    }

}
