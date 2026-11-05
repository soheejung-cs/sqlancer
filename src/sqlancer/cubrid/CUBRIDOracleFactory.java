package sqlancer.cubrid;

import java.sql.SQLException;

import sqlancer.OracleFactory;
import sqlancer.common.oracle.NoRECOracle;
import sqlancer.common.oracle.TLPWhereOracle;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.cubrid.gen.CUBRIDExpressionGenerator;
import sqlancer.cubrid.oracle.CUBRIDTLPAggregateOracle;
import sqlancer.cubrid.oracle.CUBRIDTLPHavingOracle;
import sqlancer.cubrid.oracle.CUBRIDTLPWherePreparedOracle;

public enum CUBRIDOracleFactory implements OracleFactory<CUBRIDGlobalState> {

    WHERE {
        @Override
        public TestOracle<CUBRIDGlobalState> create(CUBRIDGlobalState globalState) throws SQLException {
            CUBRIDExpressionGenerator gen = new CUBRIDExpressionGenerator(globalState);
            ExpectedErrors errors = CUBRIDErrors.getExpectedExpressionErrors();
            return new TLPWhereOracle<>(globalState, gen, errors);
        }
    },
    // COUNT(*) WHERE p against SUM(CASE WHEN p THEN 1 ELSE 0 END): the optimizer against plain evaluation
    NOREC {
        @Override
        public TestOracle<CUBRIDGlobalState> create(CUBRIDGlobalState globalState) throws SQLException {
            CUBRIDExpressionGenerator gen = new CUBRIDExpressionGenerator(globalState);
            ExpectedErrors errors = CUBRIDErrors.getExpectedExpressionErrors();
            return new NoRECOracle<>(globalState, gen, errors);
        }
    },
    HAVING {
        @Override
        public TestOracle<CUBRIDGlobalState> create(CUBRIDGlobalState globalState) throws SQLException {
            return new CUBRIDTLPHavingOracle(globalState);
        }
    },
    AGGREGATE {
        @Override
        public TestOracle<CUBRIDGlobalState> create(CUBRIDGlobalState globalState) throws SQLException {
            return new CUBRIDTLPAggregateOracle(globalState);
        }
    },
    WHERE_PREPARED {
        @Override
        public TestOracle<CUBRIDGlobalState> create(CUBRIDGlobalState globalState) throws SQLException {
            return new CUBRIDTLPWherePreparedOracle(globalState);
        }
    };

}
