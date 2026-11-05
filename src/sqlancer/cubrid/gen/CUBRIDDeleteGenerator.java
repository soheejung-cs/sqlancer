package sqlancer.cubrid.gen;

import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.cubrid.CUBRIDErrors;
import sqlancer.cubrid.CUBRIDGlobalState;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDDataType;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDTable;
import sqlancer.cubrid.CUBRIDToStringVisitor;

public final class CUBRIDDeleteGenerator {

    private CUBRIDDeleteGenerator() {
    }

    // DELETE FROM t [WHERE <predicate over t>]: mostly with a WHERE, so that a table rarely ends up empty
    public static SQLQueryAdapter getQuery(CUBRIDGlobalState globalState) {
        CUBRIDTable table = globalState.getSchema().getRandomTable(t -> !t.isView());
        StringBuilder sb = new StringBuilder("DELETE FROM ");
        sb.append(table.getName());
        if (!Randomly.getBooleanWithSmallProbability()) {
            CUBRIDExpressionGenerator gen = new CUBRIDExpressionGenerator(globalState).setColumns(table.getColumns());
            sb.append(" WHERE ");
            sb.append(
                    CUBRIDToStringVisitor.asString(gen.withoutSubqueries().generateExpression(CUBRIDDataType.BOOLEAN)));
        }
        ExpectedErrors errors = new ExpectedErrors();
        CUBRIDErrors.addExpressionErrors(errors);
        return new SQLQueryAdapter(sb.toString(), errors);
    }

}
