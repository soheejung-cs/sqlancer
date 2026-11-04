package sqlancer.cubrid.ast;

import sqlancer.common.ast.newast.NewBetweenOperatorNode;

public class CUBRIDBetweenOperation extends NewBetweenOperatorNode<CUBRIDExpression> implements CUBRIDExpression {

    public CUBRIDBetweenOperation(CUBRIDExpression left, CUBRIDExpression middle, CUBRIDExpression right,
            boolean isTrue) {
        super(left, middle, right, isTrue);
    }

}
