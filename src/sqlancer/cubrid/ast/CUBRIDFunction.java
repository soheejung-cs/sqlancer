package sqlancer.cubrid.ast;

import java.util.List;

import sqlancer.common.ast.newast.NewFunctionNode;

public class CUBRIDFunction<F> extends NewFunctionNode<CUBRIDExpression, F> implements CUBRIDExpression {

    public CUBRIDFunction(List<CUBRIDExpression> args, F func) {
        super(args, func);
    }

}
