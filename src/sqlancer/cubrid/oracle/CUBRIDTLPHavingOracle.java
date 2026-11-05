package sqlancer.cubrid.oracle;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.ComparatorHelper;
import sqlancer.Randomly;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.oracle.TestOracleUtils;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.schema.AbstractTables;
import sqlancer.cubrid.CUBRIDErrors;
import sqlancer.cubrid.CUBRIDGlobalState;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDColumn;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDDataType;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDTable;
import sqlancer.cubrid.ast.CUBRIDColumnReference;
import sqlancer.cubrid.ast.CUBRIDExpression;
import sqlancer.cubrid.ast.CUBRIDSelect;
import sqlancer.cubrid.gen.CUBRIDExpressionGenerator;

// TLP HAVING: the groups of SELECT ... GROUP BY g are those of
//   SELECT ... GROUP BY g HAVING p UNION ALL ... HAVING NOT p UNION ALL ... HAVING (p) IS NULL
// The first column, which the comparison looks at, is a grouped column or an aggregate of the group.
public class CUBRIDTLPHavingOracle implements TestOracle<CUBRIDGlobalState> {

    private final CUBRIDGlobalState state;
    private final ExpectedErrors errors;
    private String lastQueryString;

    public CUBRIDTLPHavingOracle(CUBRIDGlobalState state) {
        this.state = state;
        this.errors = CUBRIDErrors.getExpectedExpressionErrors();
    }

    @Override
    public void check() throws SQLException {
        AbstractTables<CUBRIDTable, CUBRIDColumn> targetTables = TestOracleUtils
                .getRandomTableNonEmptyTables(state.getSchema());
        CUBRIDExpressionGenerator gen = new CUBRIDExpressionGenerator(state).setTablesAndColumns(targetTables);
        CUBRIDSelect select = gen.generateSelect();
        select.setJoinClauses(gen.getRandomJoinClauses());
        select.setFromList(gen.getTableRefs());
        if (Randomly.getBoolean()) {
            select.setWhereClause(gen.generateBooleanExpression());
        }
        List<CUBRIDColumn> groupColumns = Randomly.nonEmptySubset(targetTables.getColumns());
        List<CUBRIDExpression> groupBy = groupColumns.stream().map(CUBRIDColumnReference::new)
                .collect(Collectors.toList());
        select.setGroupByExpressions(groupBy);
        List<CUBRIDExpression> fetchColumns = new ArrayList<>();
        fetchColumns.add(Randomly.getBoolean() ? Randomly.fromList(groupBy) : gen.generateAggregate(
                Randomly.fromOptions(CUBRIDDataType.INT, CUBRIDDataType.DOUBLE, CUBRIDDataType.VARCHAR)));
        fetchColumns.addAll(groupBy);
        select.setFetchColumns(fetchColumns);

        String originalQueryString = select.asString();
        lastQueryString = originalQueryString;
        List<String> resultSet = ComparatorHelper.getResultSetFirstColumnAsString(originalQueryString, errors, state);

        CUBRIDExpression predicate = gen.generateHavingPredicate(groupColumns);
        select.setHavingClause(predicate);
        String firstQueryString = select.asString();
        select.setHavingClause(gen.negatePredicate(predicate));
        String secondQueryString = select.asString();
        select.setHavingClause(gen.isNull(predicate));
        String thirdQueryString = select.asString();
        List<String> combinedString = new ArrayList<>();
        List<String> secondResultSet = ComparatorHelper.getCombinedResultSet(firstQueryString, secondQueryString,
                thirdQueryString, combinedString, true, state, errors);
        ComparatorHelper.assumeResultSetsAreEqual(resultSet, secondResultSet, originalQueryString, combinedString,
                state);
    }

    @Override
    public String getLastQueryString() {
        return lastQueryString;
    }

}
