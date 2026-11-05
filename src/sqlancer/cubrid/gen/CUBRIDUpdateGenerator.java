package sqlancer.cubrid.gen;

import java.util.List;

import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.cubrid.CUBRIDErrors;
import sqlancer.cubrid.CUBRIDGlobalState;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDColumn;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDDataType;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDTable;
import sqlancer.cubrid.CUBRIDToStringVisitor;

public final class CUBRIDUpdateGenerator {

    private CUBRIDUpdateGenerator() {
    }

    // UPDATE t SET c = <constant or expression over t> [, ...] [WHERE <predicate over t>]
    public static SQLQueryAdapter getQuery(CUBRIDGlobalState globalState) {
        CUBRIDTable table = globalState.getSchema().getRandomTable(t -> !t.isView());
        CUBRIDExpressionGenerator gen = new CUBRIDExpressionGenerator(globalState).setColumns(table.getColumns());
        gen.withoutSubqueries();
        StringBuilder sb = new StringBuilder("UPDATE ");
        sb.append(table.getName());
        sb.append(" SET ");
        List<CUBRIDColumn> columns = table.getRandomNonEmptyColumnSubset();
        for (int i = 0; i < columns.size(); i++) {
            if (i != 0) {
                sb.append(", ");
            }
            CUBRIDColumn column = columns.get(i);
            sb.append(column.getName());
            sb.append(" = ");
            sb.append(CUBRIDToStringVisitor.asString(Randomly.getBoolean() ? gen.generateConstant(column.getType())
                    : gen.generateExpression(column.getType())));
        }
        if (Randomly.getBoolean()) {
            sb.append(" WHERE ");
            sb.append(CUBRIDToStringVisitor.asString(gen.generateExpression(CUBRIDDataType.BOOLEAN)));
        }
        ExpectedErrors errors = new ExpectedErrors();
        CUBRIDErrors.addInsertErrors(errors);
        CUBRIDErrors.addExpressionErrors(errors);
        errors.add("cannot be made NULL"); // a NOT NULL column set to an expression that folds to NULL
        return new SQLQueryAdapter(sb.toString(), errors);
    }

}
