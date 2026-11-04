package sqlancer.cubrid.ast;

import sqlancer.common.ast.newast.ColumnReferenceNode;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDColumn;

public class CUBRIDColumnReference extends ColumnReferenceNode<CUBRIDExpression, CUBRIDColumn>
        implements CUBRIDExpression {

    public CUBRIDColumnReference(CUBRIDColumn column) {
        super(column);
    }

}
