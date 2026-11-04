package sqlancer.cubrid.ast;

import java.util.ArrayList;
import java.util.List;

import sqlancer.Randomly;
import sqlancer.common.ast.newast.Join;
import sqlancer.cubrid.CUBRIDBugs;
import sqlancer.cubrid.CUBRIDGlobalState;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDColumn;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDDataType;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDTable;
import sqlancer.cubrid.gen.CUBRIDExpressionGenerator;

public class CUBRIDJoin implements CUBRIDExpression, Join<CUBRIDExpression, CUBRIDTable, CUBRIDColumn> {

    private final CUBRIDTableReference leftTable;
    private final CUBRIDTableReference rightTable;
    private final JoinType joinType;
    private CUBRIDExpression onCondition;

    // CUBRID has no FULL OUTER JOIN
    public enum JoinType {
        INNER("INNER JOIN"), LEFT("LEFT OUTER JOIN"), RIGHT("RIGHT OUTER JOIN"), CROSS("CROSS JOIN");

        private final String textRepresentation;

        JoinType(String textRepresentation) {
            this.textRepresentation = textRepresentation;
        }

        public String getTextRepresentation() {
            return textRepresentation;
        }

        public static JoinType getRandom() {
            return Randomly.fromOptions(values());
        }
    }

    public CUBRIDJoin(CUBRIDTableReference leftTable, CUBRIDTableReference rightTable, JoinType joinType,
            CUBRIDExpression onCondition) {
        this.leftTable = leftTable;
        this.rightTable = rightTable;
        this.joinType = joinType;
        this.onCondition = onCondition;
    }

    public CUBRIDTableReference getLeftTable() {
        return leftTable;
    }

    public CUBRIDTableReference getRightTable() {
        return rightTable;
    }

    public JoinType getJoinType() {
        return joinType;
    }

    public CUBRIDExpression getOnCondition() {
        return onCondition;
    }

    @Override
    public void setOnClause(CUBRIDExpression onClause) {
        onCondition = onClause;
    }

    // Takes the tables of each join off the front of tableList: what is left stays in the FROM list.
    public static List<CUBRIDJoin> getJoins(List<CUBRIDTableReference> tableList, CUBRIDGlobalState globalState) {
        List<CUBRIDJoin> joins = new ArrayList<>();
        while (tableList.size() >= 2 && Randomly.getBooleanWithRatherLowProbability()) {
            CUBRIDTableReference left = tableList.remove(0);
            CUBRIDTableReference right = tableList.remove(0);
            List<CUBRIDColumn> columns = new ArrayList<>(left.getTable().getColumns());
            columns.addAll(right.getTable().getColumns());
            JoinType joinType = CUBRIDBugs.outerJoinReducedByNullablePredicate
                    ? Randomly.fromOptions(JoinType.INNER, JoinType.CROSS) : JoinType.getRandom();
            CUBRIDExpression on = null;
            if (joinType != JoinType.CROSS) {
                CUBRIDExpressionGenerator gen = new CUBRIDExpressionGenerator(globalState).setColumns(columns);
                on = gen.generateExpression(CUBRIDDataType.BOOLEAN);
            }
            joins.add(new CUBRIDJoin(left, right, joinType, on));
        }
        return joins;
    }

}
