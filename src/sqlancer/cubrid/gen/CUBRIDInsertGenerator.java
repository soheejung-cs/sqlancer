package sqlancer.cubrid.gen;

import java.util.List;

import sqlancer.common.gen.AbstractInsertGenerator;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.cubrid.CUBRIDErrors;
import sqlancer.cubrid.CUBRIDGlobalState;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDColumn;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDTable;
import sqlancer.cubrid.CUBRIDToStringVisitor;

public class CUBRIDInsertGenerator extends AbstractInsertGenerator<CUBRIDColumn> {

    private final CUBRIDGlobalState globalState;
    private final CUBRIDExpressionGenerator gen;

    public CUBRIDInsertGenerator(CUBRIDGlobalState globalState) {
        this.globalState = globalState;
        this.gen = new CUBRIDExpressionGenerator(globalState);
    }

    public static SQLQueryAdapter getQuery(CUBRIDGlobalState globalState) {
        return new CUBRIDInsertGenerator(globalState).getStatement();
    }

    @Override
    public void buildStatement() {
        CUBRIDTable table = globalState.getSchema().getRandomTable(t -> !t.isView());
        List<CUBRIDColumn> columns = table.getRandomNonEmptyColumnSubset();
        buildInsertInto(table.getName(), columns);
        CUBRIDErrors.addInsertErrors(errors);
        CUBRIDErrors.addExpressionErrors(errors);
    }

    @Override
    protected void insertValue(CUBRIDColumn column) {
        sb.append(CUBRIDToStringVisitor.asString(gen.generateConstant(column.getType())));
    }

}
