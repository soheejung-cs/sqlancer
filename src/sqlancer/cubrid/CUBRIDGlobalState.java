package sqlancer.cubrid;

import java.sql.SQLException;

import sqlancer.SQLGlobalState;

public class CUBRIDGlobalState extends SQLGlobalState<CUBRIDOptions, CUBRIDSchema> {

    @Override
    protected CUBRIDSchema readSchema() throws SQLException {
        return CUBRIDSchema.fromConnection(getConnection());
    }

}
