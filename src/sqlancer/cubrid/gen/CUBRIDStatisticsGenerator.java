package sqlancer.cubrid.gen;

import sqlancer.Randomly;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.cubrid.CUBRIDGlobalState;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDTable;

public final class CUBRIDStatisticsGenerator {

    private CUBRIDStatisticsGenerator() {
    }

    // the form every CUBRID version reads: the histogram clause (", N buckets") came later
    public static SQLQueryAdapter getQuery(CUBRIDGlobalState globalState) {
        CUBRIDTable table = globalState.getSchema().getRandomTable(t -> !t.isView());
        return new SQLQueryAdapter(
                "UPDATE STATISTICS ON " + table.getName() + (Randomly.getBoolean() ? " WITH FULLSCAN" : ""));
    }

}
