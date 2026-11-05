package sqlancer.cubrid.ast;

import sqlancer.common.ast.newast.NewAliasNode;

public class CUBRIDAlias extends NewAliasNode<CUBRIDExpression> implements CUBRIDExpression {

    public CUBRIDAlias(CUBRIDExpression expr, String alias) {
        super(expr, alias);
    }

}
