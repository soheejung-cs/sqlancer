package sqlancer.cubrid.oracle;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.oracle.TestOracleUtils;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.schema.AbstractTables;
import sqlancer.cubrid.CUBRIDErrors;
import sqlancer.cubrid.CUBRIDGlobalState;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDColumn;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDTable;
import sqlancer.cubrid.CUBRIDToStringVisitor;
import sqlancer.cubrid.ast.CUBRIDExpression;
import sqlancer.cubrid.ast.CUBRIDSelect;
import sqlancer.cubrid.gen.CUBRIDExpressionGenerator;

// TLP WHERE through prepared statements: every constant (but NULL, TRUE, FALSE and a LIKE pattern) is a ? bound to
// its value, so that the plan is built for host variables and, run a second time, comes from the plan cache.
public class CUBRIDTLPWherePreparedOracle implements TestOracle<CUBRIDGlobalState> {

    private final CUBRIDGlobalState state;
    private final ExpectedErrors errors;
    private String lastQueryString;

    public CUBRIDTLPWherePreparedOracle(CUBRIDGlobalState state) {
        this.state = state;
        this.errors = CUBRIDErrors.getExpectedExpressionErrors();
        this.errors.addAll(CUBRIDErrors.getHostVariableErrors());
    }

    @Override
    public void check() throws SQLException {
        AbstractTables<CUBRIDTable, CUBRIDColumn> targetTables = TestOracleUtils
                .getRandomTableNonEmptyTables(state.getSchema());
        CUBRIDExpressionGenerator gen = new CUBRIDExpressionGenerator(state).setTablesAndColumns(targetTables);
        CUBRIDSelect select = gen.generateSelect();
        select.setFetchColumns(gen.generateFetchColumns(true));
        select.setJoinClauses(gen.getRandomJoinClauses());
        select.setFromList(gen.getTableRefs());

        List<Object> originalParameters = new ArrayList<>();
        String originalQueryString = CUBRIDToStringVisitor.asString(select, originalParameters);
        lastQueryString = originalQueryString;
        String originalLiteral = select.asString();
        List<String> resultSet = execute(originalQueryString, originalParameters);

        CUBRIDExpression predicate = gen.generateBooleanExpression();
        List<Object> parameters = new ArrayList<>();
        StringBuilder combined = new StringBuilder();
        StringBuilder combinedLiteral = new StringBuilder();
        for (CUBRIDExpression where : List.of(predicate, gen.negatePredicate(predicate), gen.isNull(predicate))) {
            select.setWhereClause(where);
            if (combined.length() != 0) {
                combined.append(" UNION ALL ");
                combinedLiteral.append(" UNION ALL ");
            }
            combined.append(CUBRIDToStringVisitor.asString(select, parameters));
            combinedLiteral.append(select.asString());
        }
        List<String> secondResultSet = execute(combined.toString(), parameters);
        if (Randomly.getBoolean()) {
            // the same statement once more: the plan now comes from the cache
            List<String> again = execute(combined.toString(), parameters);
            ComparatorHelper.assumeResultSetsAreEqual(secondResultSet, again, combinedLiteral.toString(),
                    List.of(combinedLiteral.toString()), state);
        }
        state.getState().getLocalState().log("-- prepared: " + combined + ";\n-- parameters: " + parameters);
        ComparatorHelper.assumeResultSetsAreEqual(resultSet, secondResultSet, originalLiteral,
                List.of(combinedLiteral.toString()), state);
    }

    private List<String> execute(String queryString, List<Object> parameters) throws SQLException {
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(queryString + " -- " + parameters);
        }
        List<String> result = new ArrayList<>();
        try (PreparedStatement statement = (PreparedStatement) state.getConnection().prepareStatement(queryString)) {
            for (int i = 0; i < parameters.size(); i++) {
                statement.setObject(i + 1, parameters.get(i));
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            if (errors.errorIsExpected(e.getMessage())) {
                throw new IgnoreMeException();
            }
            throw new AssertionError(queryString + " -- " + parameters, e);
        }
        return result;
    }

    @Override
    public String getLastQueryString() {
        return lastQueryString;
    }

}
