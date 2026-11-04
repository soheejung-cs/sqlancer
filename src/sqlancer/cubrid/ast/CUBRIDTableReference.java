package sqlancer.cubrid.ast;

import sqlancer.common.ast.newast.TableReferenceNode;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDTable;

public class CUBRIDTableReference extends TableReferenceNode<CUBRIDExpression, CUBRIDTable>
        implements CUBRIDExpression {

    public CUBRIDTableReference(CUBRIDTable table) {
        super(table);
    }

}
