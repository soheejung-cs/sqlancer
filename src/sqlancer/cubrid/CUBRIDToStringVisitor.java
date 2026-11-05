package sqlancer.cubrid;

import java.util.List;

import sqlancer.common.ast.newast.NewBinaryOperatorNode;
import sqlancer.common.ast.newast.NewToStringVisitor;
import sqlancer.cubrid.ast.CUBRIDBinaryOperation;
import sqlancer.cubrid.ast.CUBRIDCastNode;
import sqlancer.cubrid.ast.CUBRIDConstant;
import sqlancer.cubrid.ast.CUBRIDExistsOperation;
import sqlancer.cubrid.ast.CUBRIDExpression;
import sqlancer.cubrid.ast.CUBRIDInSubqueryOperation;
import sqlancer.cubrid.ast.CUBRIDJoin;
import sqlancer.cubrid.ast.CUBRIDQuantifiedComparison;
import sqlancer.cubrid.ast.CUBRIDSearchedCase;
import sqlancer.cubrid.ast.CUBRIDSelect;
import sqlancer.cubrid.gen.CUBRIDExpressionGenerator.CUBRIDLikeOperator;

public class CUBRIDToStringVisitor extends NewToStringVisitor<CUBRIDExpression> {

    // Given a list, the visitor writes each constant other than NULL, TRUE and FALSE as ? and collects its value in
    // order, for a prepared statement.
    private final List<Object> parameters;
    private boolean keepLiterals;

    public CUBRIDToStringVisitor() {
        this(null);
    }

    public CUBRIDToStringVisitor(List<Object> parameters) {
        this.parameters = parameters;
    }

    @Override
    public void visitSpecific(CUBRIDExpression expr) {
        if (expr instanceof CUBRIDConstant) {
            visit((CUBRIDConstant) expr);
        } else if (expr instanceof CUBRIDSelect) {
            visit((CUBRIDSelect) expr);
        } else if (expr instanceof CUBRIDJoin) {
            visit((CUBRIDJoin) expr);
        } else if (expr instanceof CUBRIDCastNode) {
            visit((CUBRIDCastNode) expr);
        } else if (expr instanceof CUBRIDExistsOperation) {
            visit((CUBRIDExistsOperation) expr);
        } else if (expr instanceof CUBRIDInSubqueryOperation) {
            visit((CUBRIDInSubqueryOperation) expr);
        } else if (expr instanceof CUBRIDQuantifiedComparison) {
            visit((CUBRIDQuantifiedComparison) expr);
        } else if (expr instanceof CUBRIDSearchedCase) {
            visit((CUBRIDSearchedCase) expr);
        } else {
            throw new AssertionError(expr.getClass());
        }
    }

    private void visit(CUBRIDConstant constant) {
        Object value = parameters == null || keepLiterals ? null : constant.getBindValue();
        if (value == null) {
            sb.append(constant.toString());
        } else {
            sb.append('?');
            parameters.add(value);
        }
    }

    // A pattern that is bound is no longer a literal and meets the LIKE range bug: it stays in the text.
    @Override
    public void visit(NewBinaryOperatorNode<CUBRIDExpression> op) {
        if (parameters == null || !CUBRIDBugs.likeRuntimePatternMissesEmptyString
                || !(op instanceof CUBRIDBinaryOperation)
                || !(((CUBRIDBinaryOperation) op).getOp() instanceof CUBRIDLikeOperator)) {
            super.visit(op);
            return;
        }
        sb.append("((");
        visit(op.getLeft());
        sb.append(")");
        sb.append(op.getOperatorRepresentation());
        sb.append("(");
        boolean saved = keepLiterals;
        keepLiterals = true;
        visit(op.getRight());
        keepLiterals = saved;
        sb.append("))");
    }

    private void visit(CUBRIDCastNode cast) {
        sb.append("CAST(");
        visit(cast.getExpression());
        sb.append(" AS ");
        sb.append(cast.getTypeName());
        sb.append(')');
    }

    private void visit(CUBRIDExistsOperation exists) {
        sb.append(exists.isNegated() ? "(NOT EXISTS (" : "(EXISTS (");
        boolean saved = keepLiterals;
        keepLiterals = saved || CUBRIDBugs.existsSelectListHostVariable;
        visit(exists.getSubquery());
        keepLiterals = saved;
        sb.append("))");
    }

    private void visit(CUBRIDInSubqueryOperation in) {
        sb.append('(');
        visit(in.getLeft());
        sb.append(in.isNegated() ? " NOT IN (" : " IN (");
        visit(in.getSubquery());
        sb.append("))");
    }

    private void visit(CUBRIDQuantifiedComparison comparison) {
        sb.append('(');
        visit(comparison.getLeft());
        sb.append(' ');
        sb.append(comparison.getOp().getTextRepresentation());
        sb.append(' ');
        sb.append(comparison.getQuantifier());
        sb.append(" (");
        visit(comparison.getSubquery());
        sb.append("))");
    }

    private void visit(CUBRIDSearchedCase searchedCase) {
        sb.append("(CASE WHEN ");
        visit(searchedCase.getCondition());
        sb.append(" THEN ");
        visit(searchedCase.getThenExpr());
        sb.append(" ELSE ");
        visit(searchedCase.getElseExpr());
        sb.append(" END)");
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

    // the text of a prepared statement: its constants become ? and their values go to parameters in order
    public static String asString(CUBRIDExpression expr, List<Object> parameters) {
        CUBRIDToStringVisitor visitor = new CUBRIDToStringVisitor(parameters);
        visitor.visit(expr);
        return visitor.get();
    }

}
