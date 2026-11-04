package sqlancer.cubrid.ast;

import java.util.List;

import sqlancer.common.ast.newast.NewCaseOperatorNode;

public class CUBRIDCaseOperation extends NewCaseOperatorNode<CUBRIDExpression> implements CUBRIDExpression {

    public CUBRIDCaseOperation(CUBRIDExpression switchCondition, List<CUBRIDExpression> conditions,
            List<CUBRIDExpression> expressions, CUBRIDExpression elseExpr) {
        super(switchCondition, conditions, expressions, elseExpr);
    }

}
