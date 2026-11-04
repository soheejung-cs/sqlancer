package sqlancer.cubrid.ast;

import java.util.List;

import sqlancer.common.ast.newast.NewInOperatorNode;

public class CUBRIDInOperation extends NewInOperatorNode<CUBRIDExpression> implements CUBRIDExpression {

    public CUBRIDInOperation(CUBRIDExpression left, List<CUBRIDExpression> right, boolean isNegated) {
        super(left, right, isNegated);
    }

}
