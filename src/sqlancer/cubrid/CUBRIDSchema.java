package sqlancer.cubrid;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sqlancer.Randomly;
import sqlancer.SQLConnection;
import sqlancer.common.schema.AbstractRelationalTable;
import sqlancer.common.schema.AbstractSchema;
import sqlancer.common.schema.AbstractTableColumn;
import sqlancer.common.schema.AbstractTables;
import sqlancer.common.schema.TableIndex;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDTable;

public class CUBRIDSchema extends AbstractSchema<CUBRIDGlobalState, CUBRIDTable> {

    // The kinds of value the generators keep apart. CUBRID takes no number as a logical operand ("operand must be
    // logical expression") and reads a string as a number only when it looks like one ("Cannot coerce"), so a
    // predicate and a string never stand where a number does. BOOLEAN is the type of a predicate: CUBRID has no
    // BOOLEAN column.
    public enum CUBRIDDataType {
        BOOLEAN, INT, DOUBLE, VARCHAR;

        public static CUBRIDDataType getRandomColumnType() {
            return Randomly.fromOptions(INT, DOUBLE, VARCHAR);
        }

        public boolean isNumeric() {
            return this == INT || this == DOUBLE;
        }
    }

    public static class CUBRIDColumn extends AbstractTableColumn<CUBRIDTable, CUBRIDDataType> {

        public CUBRIDColumn(String name, CUBRIDDataType type) {
            super(name, null, type);
        }

    }

    public static class CUBRIDTables extends AbstractTables<CUBRIDTable, CUBRIDColumn> {

        public CUBRIDTables(List<CUBRIDTable> tables) {
            super(tables);
        }

    }

    public static class CUBRIDTable extends AbstractRelationalTable<CUBRIDColumn, TableIndex, CUBRIDGlobalState> {

        public CUBRIDTable(String tableName, List<CUBRIDColumn> columns, List<TableIndex> indexes, boolean isView) {
            super(tableName, columns, indexes, isView);
        }

    }

    public CUBRIDSchema(List<CUBRIDTable> databaseTables) {
        super(databaseTables);
    }

    public static CUBRIDSchema fromConnection(SQLConnection con) throws SQLException {
        Map<String, Boolean> classes = new LinkedHashMap<>(); // class name -> is a view
        try (Statement s = con.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT class_name, class_type FROM db_class WHERE is_system_class = 'NO' AND owner_name = CURRENT_USER"
                        + " ORDER BY class_name")) {
            while (rs.next()) {
                classes.put(rs.getString(1), "VCLASS".equals(rs.getString(2)));
            }
        }
        List<CUBRIDTable> tables = new ArrayList<>();
        for (Map.Entry<String, Boolean> c : classes.entrySet()) {
            List<CUBRIDColumn> columns = getColumns(con, c.getKey());
            CUBRIDTable table = new CUBRIDTable(c.getKey(), columns, getIndexes(con, c.getKey()), c.getValue());
            for (CUBRIDColumn column : columns) {
                column.setTable(table);
            }
            tables.add(table);
        }
        return new CUBRIDSchema(tables);
    }

    private static List<CUBRIDColumn> getColumns(SQLConnection con, String className) throws SQLException {
        List<CUBRIDColumn> columns = new ArrayList<>();
        try (PreparedStatement s = (PreparedStatement) con.prepareStatement(
                "SELECT attr_name, data_type FROM db_attribute WHERE class_name = ? AND owner_name = CURRENT_USER"
                        + " ORDER BY def_order")) {
            s.setString(1, className);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    columns.add(new CUBRIDColumn(rs.getString(1), getColumnType(rs.getString(2))));
                }
            }
        }
        return columns;
    }

    private static List<TableIndex> getIndexes(SQLConnection con, String className) throws SQLException {
        List<TableIndex> indexes = new ArrayList<>();
        try (PreparedStatement s = (PreparedStatement) con.prepareStatement(
                "SELECT index_name FROM db_index WHERE class_name = ? AND owner_name = CURRENT_USER")) {
            s.setString(1, className);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    indexes.add(TableIndex.create(rs.getString(1)));
                }
            }
        }
        return indexes;
    }

    // db_attribute.data_type names a SMALLINT column SHORT and a VARCHAR column STRING
    private static CUBRIDDataType getColumnType(String dataType) {
        switch (dataType) {
        case "SHORT":
        case "INTEGER":
        case "BIGINT":
            return CUBRIDDataType.INT;
        case "FLOAT":
        case "DOUBLE":
            return CUBRIDDataType.DOUBLE;
        case "STRING":
        case "CHAR":
            return CUBRIDDataType.VARCHAR;
        default:
            throw new AssertionError(dataType);
        }
    }

}
