package sqlancer.cubrid.ast;

import java.util.List;
import java.util.stream.Collectors;

import sqlancer.common.ast.SelectBase;
import sqlancer.common.ast.newast.Select;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDColumn;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDTable;
import sqlancer.cubrid.CUBRIDToStringVisitor;

public class CUBRIDSelect extends SelectBase<CUBRIDExpression>
        implements CUBRIDExpression, Select<CUBRIDJoin, CUBRIDExpression, CUBRIDTable, CUBRIDColumn> {

    @Override
    public void setJoinClauses(List<CUBRIDJoin> joinStatements) {
        setJoinList(joinStatements.stream().map(j -> (CUBRIDExpression) j).collect(Collectors.toList()));
    }

    @Override
    public List<CUBRIDJoin> getJoinClauses() {
        return getJoinList().stream().map(j -> (CUBRIDJoin) j).collect(Collectors.toList());
    }

    @Override
    public String asString() {
        return CUBRIDToStringVisitor.asString(this);
    }

}
