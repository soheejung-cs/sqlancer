package sqlancer.cubrid.gen;

import java.util.List;

import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.cubrid.CUBRIDBugs;
import sqlancer.cubrid.CUBRIDErrors;
import sqlancer.cubrid.CUBRIDGlobalState;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDColumn;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDTable;

public final class CUBRIDIndexGenerator {

    private CUBRIDIndexGenerator() {
    }

    public static SQLQueryAdapter getQuery(CUBRIDGlobalState globalState) {
        ExpectedErrors errors = new ExpectedErrors();
        CUBRIDErrors.addIndexErrors(errors);
        CUBRIDTable table = globalState.getSchema().getRandomTable(t -> !t.isView());
        StringBuilder sb = new StringBuilder("CREATE ");
        if (Randomly.getBoolean()) {
            sb.append("UNIQUE ");
            CUBRIDErrors.addInsertErrors(errors); // the rows already there may repeat a key
        }
        sb.append("INDEX ");
        sb.append(globalState.getSchema().getFreeIndexName());
        sb.append(" ON ");
        sb.append(table.getName());
        sb.append(" (");
        List<CUBRIDColumn> columns = CUBRIDBugs.singleColumnKeys(globalState)
                ? List.of(Randomly.fromList(table.getColumns())) : table.getRandomNonEmptyColumnSubset();
        for (int i = 0; i < columns.size(); i++) {
            if (i != 0) {
                sb.append(", ");
            }
            sb.append(columns.get(i).getName());
            if (Randomly.getBoolean()) {
                sb.append(Randomly.fromOptions(" ASC", " DESC"));
            }
        }
        sb.append(')');
        return new SQLQueryAdapter(sb.toString(), errors, true);
    }

}
