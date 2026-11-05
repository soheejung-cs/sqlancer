package sqlancer.cubrid.oracle;

import java.sql.SQLException;
import java.util.List;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.ast.newast.NewFunctionNode;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.oracle.TestOracleUtils;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.query.SQLancerResultSet;
import sqlancer.common.schema.AbstractTables;
import sqlancer.cubrid.CUBRIDBugs;
import sqlancer.cubrid.CUBRIDErrors;
import sqlancer.cubrid.CUBRIDGlobalState;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDColumn;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDDataType;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDTable;
import sqlancer.cubrid.ast.CUBRIDAlias;
import sqlancer.cubrid.ast.CUBRIDColumnReference;
import sqlancer.cubrid.ast.CUBRIDExpression;
import sqlancer.cubrid.ast.CUBRIDFunction;
import sqlancer.cubrid.ast.CUBRIDSelect;
import sqlancer.cubrid.gen.CUBRIDExpressionGenerator;
import sqlancer.cubrid.gen.CUBRIDExpressionGenerator.CUBRIDAggregateFunction;

// TLP aggregate: an aggregate over the rows equals the same aggregate over the aggregates of the three partitions
//   SELECT MAX(e) FROM t = SELECT MAX(agg0) FROM (SELECT MAX(e) AS agg0 FROM t WHERE p UNION ALL ... WHERE NOT p
//                                                 UNION ALL ... WHERE (p) IS NULL) res
// COUNT and SUM add up with SUM, MIN and MAX with themselves.
public class CUBRIDTLPAggregateOracle implements TestOracle<CUBRIDGlobalState> {

    private final CUBRIDGlobalState state;
    private final ExpectedErrors errors;
    private String lastQueryString;

    public CUBRIDTLPAggregateOracle(CUBRIDGlobalState state) {
        this.state = state;
        this.errors = CUBRIDErrors.getExpectedExpressionErrors();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void check() throws SQLException {
        AbstractTables<CUBRIDTable, CUBRIDColumn> targetTables = TestOracleUtils
                .getRandomTableNonEmptyTables(state.getSchema());
        CUBRIDExpressionGenerator gen = new CUBRIDExpressionGenerator(state).setTablesAndColumns(targetTables);
        CUBRIDSelect select = gen.generateSelect();
        select.setJoinClauses(gen.getRandomJoinClauses());
        select.setFromList(gen.getTableRefs());

        // With no joined row at all, the three partitions can all be constant-false, which a known bug folds to NULL
        // (CUBRIDBugs.setOperationFoldedToNull): skip that case.
        if (CUBRIDBugs.setOperationFoldedToNull) {
            select.setFetchColumns(List.of(new CUBRIDFunction<>(
                    List.of(new CUBRIDColumnReference(new CUBRIDColumn("*", null))), CUBRIDAggregateFunction.COUNT)));
            String count = getSingleValue(select.asString());
            if (count == null || "0".equals(count)) {
                throw new IgnoreMeException();
            }
        }

        CUBRIDFunction<CUBRIDAggregateFunction> aggregate = (CUBRIDFunction<CUBRIDAggregateFunction>) gen
                .generateAggregate(
                        Randomly.fromOptions(CUBRIDDataType.INT, CUBRIDDataType.DOUBLE, CUBRIDDataType.VARCHAR));
        select.setFetchColumns(List.of(aggregate));
        String originalQueryString = select.asString();
        lastQueryString = originalQueryString;
        String firstResult = getSingleValue(originalQueryString);

        CUBRIDExpression predicate = gen.generateBooleanExpression();
        select.setFetchColumns(List.of(new CUBRIDAlias(aggregate, "agg0")));
        select.setWhereClause(predicate);
        String first = select.asString();
        select.setWhereClause(gen.negatePredicate(predicate));
        String second = select.asString();
        select.setWhereClause(gen.isNull(predicate));
        String third = select.asString();
        String metamorphicQueryString = "SELECT " + outerAggregate(aggregate) + "(agg0) FROM (" + first + " UNION ALL "
                + second + " UNION ALL " + third + ") res";
        String secondResult = getSingleValue(metamorphicQueryString);

        state.getState().getLocalState().log("--" + originalQueryString + ";\n--" + metamorphicQueryString + ";\n-- "
                + firstResult + "\n-- " + secondResult);
        if (firstResult == null ? secondResult != null
                : !firstResult.equals(secondResult) && !ComparatorHelper.isEqualDouble(firstResult, secondResult)) {
            throw new AssertionError("The aggregates mismatch (" + firstResult + " and " + secondResult + ")!\n"
                    + originalQueryString + "\n" + metamorphicQueryString);
        }
    }

    private static String outerAggregate(NewFunctionNode<CUBRIDExpression, CUBRIDAggregateFunction> aggregate) {
        switch (aggregate.getFunc()) {
        case COUNT:
        case SUM:
            return "SUM";
        default:
            return aggregate.getFunc().toString();
        }
    }

    private String getSingleValue(String queryString) throws SQLException {
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(queryString);
        }
        SQLQueryAdapter q = new SQLQueryAdapter(queryString, errors);
        try (SQLancerResultSet result = q.executeAndGet(state)) {
            if (result == null) {
                throw new IgnoreMeException();
            }
            return result.next() ? result.getString(1) : null;
        }
    }

    @Override
    public String getLastQueryString() {
        return lastQueryString;
    }

}
