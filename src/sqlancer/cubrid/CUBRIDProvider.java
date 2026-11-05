package sqlancer.cubrid;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.auto.service.AutoService;

import sqlancer.AbstractAction;
import sqlancer.DatabaseProvider;
import sqlancer.IgnoreMeException;
import sqlancer.MainOptions;
import sqlancer.Randomly;
import sqlancer.SQLConnection;
import sqlancer.SQLProviderAdapter;
import sqlancer.StatementExecutor;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.query.SQLQueryProvider;
import sqlancer.cubrid.gen.CUBRIDDeleteGenerator;
import sqlancer.cubrid.gen.CUBRIDIndexGenerator;
import sqlancer.cubrid.gen.CUBRIDInsertGenerator;
import sqlancer.cubrid.gen.CUBRIDStatisticsGenerator;
import sqlancer.cubrid.gen.CUBRIDTableGenerator;
import sqlancer.cubrid.gen.CUBRIDUpdateGenerator;

@AutoService(DatabaseProvider.class)
public class CUBRIDProvider extends SQLProviderAdapter<CUBRIDGlobalState, CUBRIDOptions> {

    // CUBRID cannot create a database from SQL, so each worker thread keeps one existing database for good. Main hands
    // every try a new database name, and a try can start on a thread whose earlier try failed while other tries still
    // run, so the slot follows the thread, not the name.
    private static final AtomicInteger NEXT_POOL_SLOT = new AtomicInteger();
    private static final ThreadLocal<Integer> POOL_SLOT = ThreadLocal.withInitial(NEXT_POOL_SLOT::getAndIncrement);
    // the name Main gives the connection test, which it runs on its own thread before any worker starts
    private static final String CONNECTION_TEST_SUFFIX = "connectiontest";
    // a server the master restarts after a crash: 2 minutes at most
    private static final int RESTART_WAIT_ATTEMPTS = 60;
    private static final long RESTART_WAIT_MILLIS = 2000;

    public CUBRIDProvider() {
        super(CUBRIDGlobalState.class, CUBRIDOptions.class);
    }

    public enum Action implements AbstractAction<CUBRIDGlobalState> {

        INSERT(CUBRIDInsertGenerator::getQuery), //
        UPDATE(CUBRIDUpdateGenerator::getQuery), //
        DELETE(CUBRIDDeleteGenerator::getQuery), //
        CREATE_INDEX(CUBRIDIndexGenerator::getQuery), //
        UPDATE_STATISTICS(CUBRIDStatisticsGenerator::getQuery);

        private final SQLQueryProvider<CUBRIDGlobalState> sqlQueryProvider;

        Action(SQLQueryProvider<CUBRIDGlobalState> sqlQueryProvider) {
            this.sqlQueryProvider = sqlQueryProvider;
        }

        @Override
        public SQLQueryAdapter getQuery(CUBRIDGlobalState state) throws Exception {
            return sqlQueryProvider.getQuery(state);
        }
    }

    private static int mapActions(CUBRIDGlobalState globalState, Action a) {
        Randomly r = globalState.getRandomly();
        switch (a) {
        case INSERT:
            return r.getInteger(0, globalState.getOptions().getMaxNumberInserts());
        case UPDATE:
            return r.getInteger(0, 4);
        case DELETE:
            return r.getInteger(0, 2);
        case CREATE_INDEX:
            return r.getInteger(0, 3);
        case UPDATE_STATISTICS:
            return r.getInteger(0, 2);
        default:
            throw new AssertionError(a);
        }
    }

    @Override
    public void generateDatabase(CUBRIDGlobalState globalState) throws Exception {
        int nrTables = (int) Randomly.getNotCachedInteger(1, globalState.getDbmsSpecificOptions().maxNumTables + 1);
        for (int i = 0; i < nrTables; i++) {
            boolean success;
            do {
                success = globalState.executeStatement(CUBRIDTableGenerator.getQuery(globalState));
            } while (!success);
        }
        StatementExecutor<CUBRIDGlobalState, Action> se = new StatementExecutor<>(globalState, Action.values(),
                CUBRIDProvider::mapActions, (q) -> {
                    if (globalState.getSchema().getDatabaseTables().isEmpty()) {
                        throw new IgnoreMeException();
                    }
                });
        se.executeStatements();
    }

    @Override
    public SQLConnection createDatabase(CUBRIDGlobalState globalState) throws SQLException {
        MainOptions options = globalState.getOptions();
        CUBRIDOptions cubridOptions = globalState.getDbmsSpecificOptions();
        String host = options.getHost() == null ? CUBRIDOptions.DEFAULT_HOST : options.getHost();
        int port = options.getPort() == MainOptions.NO_SET_PORT ? CUBRIDOptions.DEFAULT_PORT : options.getPort();
        // a database CUBRID has just created has the user dba, without a password
        String user = options.isDefaultUsername() ? "dba" : options.getUserName();
        String password = options.isDefaultPassword() ? "" : options.getPassword();
        // the connection test borrows the first database rather than take a slot from the workers
        int slot = globalState.getDatabaseName().endsWith(CONNECTION_TEST_SUFFIX) ? 0 : POOL_SLOT.get();
        String database = cubridOptions.databasePoolPrefix + slot;
        String url = String.format("jdbc:cubrid:%s:%d:%s:::?queryTimeout=%d", host, port, database,
                cubridOptions.queryTimeout);
        Connection con = connect(url, user, password, cubridOptions.debugServer);
        dropUserClasses(con);
        return new SQLConnection(con);
    }

    // A crashed debug server is back in a few seconds when the master restarts it (auto_restart_server): wait for it
    // rather than report every database the threads fail to create meanwhile. The crash itself is reported by the
    // statement that was running.
    private static Connection connect(String url, String user, String password, boolean waitForRestart)
            throws SQLException {
        for (int attempt = 0;; attempt++) {
            try {
                return DriverManager.getConnection(url, user, password);
            } catch (SQLException e) {
                String message = String.valueOf(e.getMessage());
                boolean down = message.contains("Failed to connect to database server")
                        || message.contains("has not been restarted");
                if (!waitForRestart || !down || attempt >= RESTART_WAIT_ATTEMPTS) {
                    throw e;
                }
                try {
                    Thread.sleep(RESTART_WAIT_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    // Empties the pool database. A view names its tables: class_type VCLASS sorts after CLASS, so DESC drops the
    // views first.
    private static void dropUserClasses(Connection con) throws SQLException {
        List<String> drops = new ArrayList<>();
        try (Statement s = con.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT class_name, class_type FROM db_class WHERE is_system_class = 'NO' AND owner_name = CURRENT_USER"
                        + " ORDER BY class_type DESC")) {
            while (rs.next()) {
                drops.add(("VCLASS".equals(rs.getString(2)) ? "DROP VIEW " : "DROP TABLE ") + rs.getString(1));
            }
        }
        try (Statement s = con.createStatement()) {
            for (String drop : drops) {
                s.execute(drop);
            }
        }
    }

    @Override
    public String getDBMSName() {
        return "cubrid";
    }

}
