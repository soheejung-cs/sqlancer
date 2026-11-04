package sqlancer.cubrid;

import sqlancer.common.ast.newast.NewToStringVisitor;
import sqlancer.cubrid.ast.CUBRIDCastNode;
import sqlancer.cubrid.ast.CUBRIDConstant;
import sqlancer.cubrid.ast.CUBRIDExpression;
import sqlancer.cubrid.ast.CUBRIDJoin;
import sqlancer.cubrid.ast.CUBRIDSelect;

public class CUBRIDToStringVisitor extends NewToStringVisitor<CUBRIDExpression> {

    @Override
    public void visitSpecific(CUBRIDExpression expr) {
        if (expr instanceof CUBRIDConstant) {
            sb.append(expr.toString());
        } else if (expr instanceof CUBRIDSelect) {
            visit((CUBRIDSelect) expr);
        } else if (expr instanceof CUBRIDJoin) {
            visit((CUBRIDJoin) expr);
        } else if (expr instanceof CUBRIDCastNode) {
            visit((CUBRIDCastNode) expr);
        } else {
            throw new AssertionError(expr.getClass());
        }
    }

    private void visit(CUBRIDCastNode cast) {
        sb.append("CAST(");
        visit(cast.getExpression());
        sb.append(" AS ");
        sb.append(cast.getTypeName());
        sb.append(')');
    }

    private void visit(CUBRIDJoin join) {
        visit((CUBRIDExpression) join.getLeftTable());
        sb.append(' ');
        sb.append(join.getJoinType().getTextRepresentation());
        sb.append(' ');
        visit((CUBRIDExpression) join.getRightTable());
        if (join.getOnCondition() != null) {
            sb.append(" ON ");
            visit(join.getOnCondition());
        }
    }

    private void visit(CUBRIDSelect select) {
        sb.append("SELECT ");
        visit(select.getFetchColumns());
        sb.append(" FROM ");
        visit(select.getFromList());
        if (!select.getFromList().isEmpty() && !select.getJoinList().isEmpty()) {
            sb.append(", ");
        }
        if (!select.getJoinList().isEmpty()) {
            visit(select.getJoinList());
        }
        if (select.getWhereClause() != null) {
            sb.append(" WHERE ");
            visit(select.getWhereClause());
        }
        if (!select.getGroupByExpressions().isEmpty()) {
            sb.append(" GROUP BY ");
            visit(select.getGroupByExpressions());
        }
        if (select.getHavingClause() != null) {
            sb.append(" HAVING ");
            visit(select.getHavingClause());
        }
        if (!select.getOrderByClauses().isEmpty()) {
            sb.append(" ORDER BY ");
            visit(select.getOrderByClauses());
        }
        if (select.getLimitClause() != null) {
            sb.append(" LIMIT ");
            visit(select.getLimitClause());
        }
        if (select.getOffsetClause() != null) {
            sb.append(" OFFSET ");
            visit(select.getOffsetClause());
        }
    }

    public static String asString(CUBRIDExpression expr) {
        CUBRIDToStringVisitor visitor = new CUBRIDToStringVisitor();
        visitor.visit(expr);
        return visitor.get();
    }

}
