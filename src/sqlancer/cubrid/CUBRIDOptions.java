package sqlancer.cubrid;

import java.util.Arrays;
import java.util.List;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import sqlancer.DBMSSpecificOptions;

@Parameters(separators = "=", commandDescription = "CUBRID (default port: " + CUBRIDOptions.DEFAULT_PORT
        + ", default host: " + CUBRIDOptions.DEFAULT_HOST + ")")
public class CUBRIDOptions implements DBMSSpecificOptions<CUBRIDOracleFactory> {

    public static final String DEFAULT_HOST = "localhost";
    public static final int DEFAULT_PORT = 33000; // the broker's, not the server's

    @Parameter(names = "--oracle")
    public List<CUBRIDOracleFactory> oracles = Arrays.asList(CUBRIDOracleFactory.WHERE);

    @Parameter(names = "--database-pool-prefix", description = "CUBRID cannot create a database from SQL: worker "
            + "thread i uses the existing database <prefix>i, and drops its user tables and views before each "
            + "database it generates")
    public String databasePoolPrefix = "sqlancer";

    @Parameter(names = "--test-joins", description = "Allow the generation of JOIN clauses", arity = 1)
    public boolean testJoins = true;

    @Parameter(names = "--test-subqueries", description = "Allow [NOT] EXISTS, [NOT] IN and ALL/ANY/SOME subqueries, "
            + "correlated ones among them", arity = 1)
    public boolean testSubqueries = true;

    @Parameter(names = "--max-num-tables", description = "The maximum number of tables to create")
    public int maxNumTables = 3;

    @Parameter(names = "--query-timeout", description = "Seconds the broker lets a statement run (0: no limit)")
    public int queryTimeout = 10;

    // A FLOAT compared with an INT is compared as a FLOAT, so the precision it loses shows as TLP mismatches. Those
    // are kept out of the defect list and collected apart: generate FLOAT columns only in such a campaign.
    @Parameter(names = "--test-float", description = "Generate FLOAT columns (a FLOAT compared with an INT makes a "
            + "predicate and its negation both TRUE, a known defect)", arity = 1)
    public boolean testFloat;

    @Parameter(names = "--debug-server", description = "The server is a debug build: steer clear of what takes only a "
            + "debug server down", arity = 1)
    public boolean debugServer;

    @Override
    public List<CUBRIDOracleFactory> getTestOracleFactory() {
        return oracles;
    }

}
