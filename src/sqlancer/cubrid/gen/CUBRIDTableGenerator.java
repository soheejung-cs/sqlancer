package sqlancer.cubrid.gen;

import java.util.ArrayList;
import java.util.List;

import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.cubrid.CUBRIDGlobalState;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDDataType;

public final class CUBRIDTableGenerator {

    private CUBRIDTableGenerator() {
    }

    public static SQLQueryAdapter getQuery(CUBRIDGlobalState globalState) {
        StringBuilder sb = new StringBuilder("CREATE TABLE ");
        sb.append(globalState.getSchema().getFreeTableName());
        sb.append(" (");
        List<String> columnNames = new ArrayList<>();
        for (int i = 0; i < Randomly.smallNumber() + 1; i++) {
            if (i != 0) {
                sb.append(", ");
            }
            String columnName = "c" + i;
            columnNames.add(columnName);
            sb.append(columnName);
            sb.append(' ');
            sb.append(getRandomTypeName(globalState));
            if (Randomly.getBooleanWithRatherLowProbability()) {
                sb.append(" NOT NULL");
            }
            if (Randomly.getBooleanWithRatherLowProbability()) {
                sb.append(" UNIQUE");
            }
        }
        if (Randomly.getBooleanWithRatherLowProbability()) {
            sb.append(", PRIMARY KEY (");
            sb.append(String.join(", ", Randomly.nonEmptySubset(columnNames)));
            sb.append(')');
        }
        sb.append(')');
        return new SQLQueryAdapter(sb.toString(), new ExpectedErrors(), true);
    }

    private static String getRandomTypeName(CUBRIDGlobalState globalState) {
        switch (CUBRIDDataType.getRandomColumnType()) {
        case INT:
            return Randomly.fromOptions("SMALLINT", "INTEGER", "BIGINT");
        case DOUBLE:
            return globalState.getDbmsSpecificOptions().testFloat ? Randomly.fromOptions("FLOAT", "DOUBLE") : "DOUBLE";
        case VARCHAR:
            return Randomly.getBoolean() ? "VARCHAR" : "VARCHAR(" + Randomly.getNotCachedInteger(1, 100) + ")";
        default:
            throw new AssertionError();
        }
    }

}
