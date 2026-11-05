package sqlancer.cubrid.gen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import sqlancer.Randomly;
import sqlancer.common.ast.BinaryOperatorNode.Operator;
import sqlancer.common.ast.newast.NewOrderingTerm.Ordering;
import sqlancer.common.gen.NoRECGenerator;
import sqlancer.common.gen.TLPWhereGenerator;
import sqlancer.common.gen.TypedExpressionGenerator;
import sqlancer.common.schema.AbstractTables;
import sqlancer.cubrid.CUBRIDBugs;
import sqlancer.cubrid.CUBRIDGlobalState;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDColumn;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDDataType;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDTable;
import sqlancer.cubrid.CUBRIDToStringVisitor;
import sqlancer.cubrid.ast.CUBRIDAlias;
import sqlancer.cubrid.ast.CUBRIDBetweenOperation;
import sqlancer.cubrid.ast.CUBRIDBinaryOperation;
import sqlancer.cubrid.ast.CUBRIDCaseOperation;
import sqlancer.cubrid.ast.CUBRIDCastNode;
import sqlancer.cubrid.ast.CUBRIDColumnReference;
import sqlancer.cubrid.ast.CUBRIDConstant;
import sqlancer.cubrid.ast.CUBRIDExistsOperation;
import sqlancer.cubrid.ast.CUBRIDExpression;
import sqlancer.cubrid.ast.CUBRIDFunction;
import sqlancer.cubrid.ast.CUBRIDInOperation;
import sqlancer.cubrid.ast.CUBRIDInSubqueryOperation;
import sqlancer.cubrid.ast.CUBRIDJoin;
import sqlancer.cubrid.ast.CUBRIDJoin.JoinType;
import sqlancer.cubrid.ast.CUBRIDOrderingTerm;
import sqlancer.cubrid.ast.CUBRIDQuantifiedComparison;
import sqlancer.cubrid.ast.CUBRIDQuantifiedComparison.Quantifier;
import sqlancer.cubrid.ast.CUBRIDSearchedCase;
import sqlancer.cubrid.ast.CUBRIDSelect;
import sqlancer.cubrid.ast.CUBRIDTableReference;
import sqlancer.cubrid.ast.CUBRIDUnaryPostfixOperation;
import sqlancer.cubrid.ast.CUBRIDUnaryPrefixOperation;

// Generates expressions of a given kind (CUBRIDDataType), so that a predicate stands only where CUBRID wants a logical
// expression and a string only where it wants a string: an operand CUBRID cannot take costs the query, not a bug.
public class CUBRIDExpressionGenerator extends TypedExpressionGenerator<CUBRIDExpression, CUBRIDColumn, CUBRIDDataType>
        implements TLPWhereGenerator<CUBRIDSelect, CUBRIDJoin, CUBRIDExpression, CUBRIDTable, CUBRIDColumn>,
        NoRECGenerator<CUBRIDSelect, CUBRIDJoin, CUBRIDExpression, CUBRIDTable, CUBRIDColumn> {

    // a subquery holds no subquery of its own
    private static final int MAX_SUBQUERY_DEPTH = 1;

    private final CUBRIDGlobalState globalState;
    private List<CUBRIDTable> tables = Collections.emptyList();
    private boolean allowNullConstants = true;
    // Set once the query has an ORDER BY or a GROUP BY: the predicates then leave out the forms that the sort skip
    // through an index takes for proof that a key column is not NULL (CUBRIDBugs.orderBySkipOnIsFalse).
    private boolean avoidNullableShapes;
    private int subqueryDepth;
    // subqueries in the current predicate: one at most while CUBRIDBugs.antiJoinTakesNoTableTermIntoInnerScan holds
    private int subqueryCount;
    // In a HAVING clause, a column outside an aggregate must be one of the GROUP BY columns: CUBRID takes any other
    // column too, with the value of an arbitrary row of the group, which no partition can reproduce.
    private List<CUBRIDColumn> groupColumns;
    private boolean allowAggregates;
    // In the WHERE of a subquery every leaf is a column (or a column cast to the kind), so that the WHERE cannot fold
    // to FALSE at compile time (CUBRIDBugs.constantFalseSubqueryIsNullTyped, .foldedFalseSubqueryInXaslCacheKey).
    private boolean onlyColumnLeaves;
    // In the argument of an aggregate there is no COALESCE, whose folded type a derived table over the aggregate does
    // not take (CUBRIDBugs.derivedColumnOfFoldedNullCoalesce).
    private boolean avoidCoalesce;

    public CUBRIDExpressionGenerator(CUBRIDGlobalState globalState) {
        this.globalState = globalState;
    }

    private enum BooleanExpression {
        NOT, AND_OR, COMPARISON, IS_NULL, IS_TRUTH, BETWEEN, IN, LIKE, EXISTS, IN_SUBQUERY, QUANTIFIED
    }

    private enum NumericExpression {
        UNARY, ARITHMETIC, FUNCTION, CASE, CAST, CHAR_LENGTH
    }

    private enum StringExpression {
        CONCAT, FUNCTION, SUBSTR, CASE, CAST
    }

    @Override
    protected CUBRIDExpression generateExpression(CUBRIDDataType type, int depth) {
        if (depth >= globalState.getOptions().getMaxExpressionDepth()
                || Randomly.getBooleanWithRatherLowProbability()) {
            return generateLeafNode(type);
        }
        switch (type) {
        case BOOLEAN:
            return generateBooleanExpression(depth);
        case INT:
        case DOUBLE:
            return generateNumericExpression(type, depth);
        case VARCHAR:
            return generateStringExpression(depth);
        default:
            throw new AssertionError(type);
        }
    }

    // A predicate leaf compares two leaves: a lone TRUE or FALSE says nothing about the rows.
    @Override
    public CUBRIDExpression generateLeafNode(CUBRIDDataType type) {
        if (type != CUBRIDDataType.BOOLEAN) {
            if (onlyColumnLeaves) {
                return generateColumnLeaf(type);
            }
            return groupColumns == null ? super.generateLeafNode(type) : generateHavingLeaf(type);
        }
        if (!onlyColumnLeaves && Randomly.getBooleanWithSmallProbability()) {
            return CUBRIDConstant.createBooleanConstant(Randomly.getBoolean());
        }
        CUBRIDDataType left = getRandomType();
        return comparison(generateLeafNode(left), generateLeafNode(comparableType(left)));
    }

    // a column of the kind, or a column of another kind cast to it
    private CUBRIDExpression generateColumnLeaf(CUBRIDDataType type) {
        if (canGenerateColumnOfType(type)) {
            return generateColumn(type);
        }
        CUBRIDColumn column = Randomly.fromList(columns);
        String typeName = type == CUBRIDDataType.INT ? "BIGINT" : type.toString();
        return new CUBRIDCastNode(new CUBRIDColumnReference(column), typeName);
    }

    private CUBRIDExpression comparison(CUBRIDExpression left, CUBRIDExpression right) {
        CUBRIDComparisonOperator op = CUBRIDComparisonOperator.getRandom();
        // c < c, c > c and c <> c fold to FALSE at compile time, and with them the WHERE of a subquery
        if (onlyColumnLeaves
                && (op == CUBRIDComparisonOperator.LESS || op == CUBRIDComparisonOperator.GREATER
                        || op == CUBRIDComparisonOperator.NOT_EQUALS || op == CUBRIDComparisonOperator.BANG_EQUALS)
                && CUBRIDToStringVisitor.asString(left).equals(CUBRIDToStringVisitor.asString(right))) {
            op = Randomly.fromOptions(CUBRIDComparisonOperator.EQUALS, CUBRIDComparisonOperator.LESS_EQUALS,
                    CUBRIDComparisonOperator.GREATER_EQUALS);
        }
        if (op == CUBRIDComparisonOperator.NULL_SAFE_EQUALS && CUBRIDBugs.nullSafeEqualsOfTwoMinuses
                && hasUnaryMinus(left) && hasUnaryMinus(right)) {
            op = CUBRIDComparisonOperator.EQUALS;
        }
        return new CUBRIDBinaryOperation(left, right, op);
    }

    // a unary minus anywhere in the operand, not only on top: +(-x), and a CASE, GREATEST or LEAST that folds to -x,
    // reach the same rewrite. A unary minus prints as "(- x)", a subtraction as "((a)-(b))".
    private static boolean hasUnaryMinus(CUBRIDExpression expr) {
        return CUBRIDToStringVisitor.asString(expr).contains("(- ");
    }

    private CUBRIDExpression generateBooleanExpression(int depth) {
        List<BooleanExpression> kinds = new ArrayList<>(Arrays.asList(BooleanExpression.values()));
        if (avoidNullableShapes) {
            kinds.removeAll(List.of(BooleanExpression.IN, BooleanExpression.BETWEEN, BooleanExpression.IS_TRUTH));
        }
        if (!canGenerateSubquery()) {
            kinds.removeAll(
                    List.of(BooleanExpression.EXISTS, BooleanExpression.IN_SUBQUERY, BooleanExpression.QUANTIFIED));
        }
        if (onlyColumnLeaves) {
            // c IS NULL next to a comparison of c is a contradiction CUBRID folds to FALSE all the same
            kinds.removeAll(List.of(BooleanExpression.IS_NULL, BooleanExpression.IS_TRUTH));
        }
        CUBRIDDataType type;
        switch (Randomly.fromList(kinds)) {
        case NOT:
            return new CUBRIDUnaryPrefixOperation(generateExpression(CUBRIDDataType.BOOLEAN, depth + 1),
                    CUBRIDUnaryPrefixOperator.NOT);
        case AND_OR:
            return new CUBRIDBinaryOperation(generateExpression(CUBRIDDataType.BOOLEAN, depth + 1),
                    generateExpression(CUBRIDDataType.BOOLEAN, depth + 1), CUBRIDLogicalOperator.getRandom());
        case COMPARISON:
            type = getRandomType();
            return comparison(generateExpression(type, depth + 1), generateExpression(comparableType(type), depth + 1));
        case IS_NULL:
            // IS [NOT] NULL over a predicate is one of the forms the NOT NULL proofs get wrong
            type = avoidNullableShapes ? CUBRIDDataType.getRandomColumnType()
                    : Randomly.fromOptions(CUBRIDDataType.values());
            return new CUBRIDUnaryPostfixOperation(generateExpression(type, depth + 1),
                    Randomly.fromOptions(CUBRIDPostfixOperator.IS_NULL, CUBRIDPostfixOperator.IS_NOT_NULL));
        case IS_TRUTH:
            return new CUBRIDUnaryPostfixOperation(generateExpression(CUBRIDDataType.BOOLEAN, depth + 1),
                    Randomly.fromOptions(CUBRIDPostfixOperator.IS_TRUE, CUBRIDPostfixOperator.IS_NOT_TRUE,
                            CUBRIDPostfixOperator.IS_FALSE, CUBRIDPostfixOperator.IS_NOT_FALSE,
                            CUBRIDPostfixOperator.IS_UNKNOWN, CUBRIDPostfixOperator.IS_NOT_UNKNOWN));
        case BETWEEN:
            type = getRandomType();
            // the bounds keep the kind of the operand while CUBRIDBugs.betweenNegationCastsToDouble
            CUBRIDDataType lower = CUBRIDBugs.betweenNegationCastsToDouble ? type : comparableType(type);
            CUBRIDDataType upper = CUBRIDBugs.betweenNegationCastsToDouble ? type : comparableType(type);
            return new CUBRIDBetweenOperation(generateExpression(type, depth + 1), generateExpression(lower, depth + 1),
                    generateExpression(upper, depth + 1), Randomly.getBoolean());
        case IN:
            type = getRandomType();
            List<CUBRIDExpression> list = new ArrayList<>();
            for (int i = 0; i < Randomly.smallNumber() + 1; i++) {
                list.add(generateExpression(comparableType(type), depth + 1));
            }
            return new CUBRIDInOperation(generateExpression(type, depth + 1), list, Randomly.getBoolean());
        case LIKE:
            return new CUBRIDBinaryOperation(generateExpression(CUBRIDDataType.VARCHAR, depth + 1),
                    generateLikePattern(depth + 1), CUBRIDLikeOperator.getRandom());
        case EXISTS:
            return new CUBRIDExistsOperation(
                    generateSubquery(randomTable(), CUBRIDConstant.createIntConstant(1), true, depth),
                    Randomly.getBoolean());
        case IN_SUBQUERY:
            return generateInSubquery(depth);
        case QUANTIFIED:
            return generateQuantifiedComparison(depth);
        default:
            throw new AssertionError();
        }
    }

    private boolean canGenerateSubquery() {
        return globalState.getDbmsSpecificOptions().testSubqueries && subqueryDepth < MAX_SUBQUERY_DEPTH
                && groupColumns == null && !globalState.getSchema().getDatabaseTables().isEmpty()
                && !(CUBRIDBugs.antiJoinTakesNoTableTermIntoInnerScan && subqueryCount > 0);
    }

    private CUBRIDTable randomTable() {
        return Randomly.fromList(globalState.getSchema().getDatabaseTables());
    }

    private CUBRIDExpression generateInSubquery(int depth) {
        CUBRIDTable table = randomTable();
        CUBRIDColumn column = Randomly.fromList(table.getColumns());
        CUBRIDSelect subquery = generateSubquery(table, new CUBRIDColumnReference(column), true, depth);
        return new CUBRIDInSubqueryOperation(generateExpression(comparableType(column.getType()), depth + 1), subquery,
                Randomly.getBoolean());
    }

    private CUBRIDExpression generateQuantifiedComparison(int depth) {
        CUBRIDTable table = randomTable();
        CUBRIDColumn column = Randomly.fromList(table.getColumns());
        CUBRIDSelect subquery = generateSubquery(table, new CUBRIDColumnReference(column), true, depth);
        CUBRIDComparisonOperator op = Randomly.fromOptions(CUBRIDComparisonOperator.EQUALS,
                CUBRIDComparisonOperator.NOT_EQUALS, CUBRIDComparisonOperator.LESS,
                CUBRIDComparisonOperator.LESS_EQUALS, CUBRIDComparisonOperator.GREATER,
                CUBRIDComparisonOperator.GREATER_EQUALS);
        return new CUBRIDQuantifiedComparison(generateExpression(comparableType(column.getType()), depth + 1), op,
                Randomly.fromOptions(Quantifier.values()), subquery);
    }

    // SELECT fetch FROM table [WHERE p]: p sees the columns of the table and those of the outer query, which makes the
    // subquery a correlated one when it names an outer column
    private CUBRIDSelect generateSubquery(CUBRIDTable table, CUBRIDExpression fetch, boolean withWhere, int depth) {
        subqueryCount++;
        CUBRIDSelect select = new CUBRIDSelect();
        select.setFetchColumns(List.of(fetch));
        select.setFromList(List.of(new CUBRIDTableReference(table)));
        if (withWhere && !Randomly.getBooleanWithSmallProbability()) {
            List<CUBRIDColumn> visible = new ArrayList<>(table.getColumns());
            if (!CUBRIDBugs.antiJoinTakesNoTableTermIntoInnerScan
                    && (!CUBRIDBugs.parallelUnionCorrelatedSubqueryCrash || !readsSeveralTables())) {
                visible.addAll(columns);
            }
            CUBRIDExpressionGenerator inner = new CUBRIDExpressionGenerator(globalState).setColumns(visible);
            inner.tables = List.of(table);
            inner.subqueryDepth = subqueryDepth + 1;
            inner.avoidNullableShapes = avoidNullableShapes;
            inner.allowNullConstants = allowNullConstants;
            inner.onlyColumnLeaves = CUBRIDBugs.constantFalseSubqueryIsNullTyped
                    || CUBRIDBugs.foldedFalseSubqueryInXaslCacheKey;
            select.setWhereClause(inner.generateExpression(CUBRIDDataType.BOOLEAN, depth + 1));
        }
        return select;
    }

    // The outer query reads more than one table, in its FROM or in its joins: getRandomJoinClauses () takes the joined
    // tables out of tables, the columns keep them.
    private boolean readsSeveralTables() {
        return columns.stream().map(CUBRIDColumn::getTable).distinct().count() > 1;
    }

    // every leaf a column, so that no term reads no table (CUBRIDBugs.outerJoinOnTermWithoutTableFiltersAll)
    public CUBRIDExpressionGenerator withOnlyColumnLeaves() {
        onlyColumnLeaves = true;
        return this;
    }

    // for an ON condition or a DML statement, where a subquery is not wanted
    public CUBRIDExpressionGenerator withoutSubqueries() {
        subqueryDepth = MAX_SUBQUERY_DEPTH;
        return this;
    }

    private CUBRIDExpression generateLikePattern(int depth) {
        // a literal pattern is folded at compile time, out of the way of the LIKE range bug
        if (!CUBRIDBugs.likeRuntimePatternMissesEmptyString && Randomly.getBoolean()) {
            return generateExpression(CUBRIDDataType.VARCHAR, depth);
        }
        StringBuilder pattern = new StringBuilder(getString());
        for (int i = 0; i < Randomly.smallNumber() + 1; i++) {
            char wildcard = avoidPercent() ? '_' : Randomly.fromOptions('%', '_');
            pattern.insert((int) Randomly.getNotCachedInteger(0, pattern.length() + 1), wildcard);
        }
        return CUBRIDConstant.createStringConstant(pattern.toString());
    }

    private CUBRIDExpression generateNumericExpression(CUBRIDDataType type, int depth) {
        switch (Randomly.fromOptions(NumericExpression.values())) {
        case UNARY:
            return new CUBRIDUnaryPrefixOperation(generateExpression(type, depth + 1),
                    Randomly.fromOptions(CUBRIDUnaryPrefixOperator.PLUS, CUBRIDUnaryPrefixOperator.MINUS));
        case ARITHMETIC:
            // INT with INT stays INT (/ truncates, % is MOD); one DOUBLE operand makes the result a DOUBLE
            if (type == CUBRIDDataType.INT) {
                return new CUBRIDBinaryOperation(generateExpression(CUBRIDDataType.INT, depth + 1),
                        generateExpression(CUBRIDDataType.INT, depth + 1),
                        avoidPercent()
                                ? Randomly.fromOptions(CUBRIDArithmeticOperator.ADD, CUBRIDArithmeticOperator.SUB,
                                        CUBRIDArithmeticOperator.MUL, CUBRIDArithmeticOperator.DIV)
                                : Randomly.fromOptions(CUBRIDArithmeticOperator.values()));
            }
            CUBRIDDataType other = Randomly.fromOptions(CUBRIDDataType.INT, CUBRIDDataType.DOUBLE);
            CUBRIDExpression left = generateExpression(CUBRIDDataType.DOUBLE, depth + 1);
            CUBRIDExpression right = generateExpression(other, depth + 1);
            if (Randomly.getBoolean()) {
                CUBRIDExpression swap = left;
                left = right;
                right = swap;
            }
            return new CUBRIDBinaryOperation(left, right, Randomly.fromOptions(CUBRIDArithmeticOperator.ADD,
                    CUBRIDArithmeticOperator.SUB, CUBRIDArithmeticOperator.MUL, CUBRIDArithmeticOperator.DIV));
        case FUNCTION:
            return generateFunction(type, depth);
        case CASE:
            return generateCase(type, depth);
        case CAST:
            if (type == CUBRIDDataType.INT) {
                return new CUBRIDCastNode(generateExpression(CUBRIDDataType.DOUBLE, depth + 1),
                        Randomly.fromOptions("INTEGER", "BIGINT"));
            }
            return new CUBRIDCastNode(generateExpression(CUBRIDDataType.INT, depth + 1), "DOUBLE");
        case CHAR_LENGTH:
            if (type == CUBRIDDataType.INT) {
                return new CUBRIDFunction<>(List.of(generateExpression(CUBRIDDataType.VARCHAR, depth + 1)),
                        CUBRIDFunc.CHAR_LENGTH);
            }
            return generateFunction(type, depth);
        default:
            throw new AssertionError();
        }
    }

    private CUBRIDExpression generateStringExpression(int depth) {
        switch (Randomly.fromOptions(StringExpression.values())) {
        case CONCAT:
            return new CUBRIDBinaryOperation(generateExpression(CUBRIDDataType.VARCHAR, depth + 1),
                    generateExpression(CUBRIDDataType.VARCHAR, depth + 1), CUBRIDStringOperator.CONCAT);
        case FUNCTION:
            return generateFunction(CUBRIDDataType.VARCHAR, depth);
        case SUBSTR:
            return new CUBRIDFunction<>(List.of(generateExpression(CUBRIDDataType.VARCHAR, depth + 1),
                    generateExpression(CUBRIDDataType.INT, depth + 1),
                    generateExpression(CUBRIDDataType.INT, depth + 1)), CUBRIDFunc.SUBSTR);
        case CASE:
            return generateCase(CUBRIDDataType.VARCHAR, depth);
        case CAST:
            return new CUBRIDCastNode(
                    generateExpression(Randomly.fromOptions(CUBRIDDataType.INT, CUBRIDDataType.DOUBLE), depth + 1),
                    "VARCHAR");
        default:
            throw new AssertionError();
        }
    }

    // a function whose arguments are of the kind it returns
    private CUBRIDExpression generateFunction(CUBRIDDataType type, int depth) {
        List<CUBRIDFunc> functions = CUBRIDFunc.getFunctions(type);
        if (avoidCoalesce) {
            functions = functions.stream().filter(f -> f != CUBRIDFunc.COALESCE).collect(Collectors.toList());
        }
        if (avoidNullableShapes) {
            // NULLIF (5, c) is 5 when c is NULL, but the list of operators that can be true on NULL leaves it out
            functions = functions.stream().filter(f -> f != CUBRIDFunc.NULLIF).collect(Collectors.toList());
        }
        CUBRIDFunc func = Randomly.fromList(functions);
        return new CUBRIDFunction<>(generateExpressions(type, func.getNrArgs(), depth + 1), func);
    }

    private CUBRIDExpression generateCase(CUBRIDDataType type, int depth) {
        CUBRIDDataType switchType = getRandomType();
        int nr = Randomly.smallNumber() + 1;
        CUBRIDExpression switchCondition = generateExpression(switchType, depth + 1);
        List<CUBRIDExpression> conditions = generateExpressions(comparableType(switchType), nr, depth + 1);
        boolean saved = allowNullConstants;
        if (CUBRIDBugs.caseFoldWithNullBranchCrash) {
            allowNullConstants = false; // a NULL branch is what the crash needs
        }
        List<CUBRIDExpression> expressions = generateExpressions(type, nr, depth + 1);
        CUBRIDExpression elseExpr = generateExpression(type, depth + 1);
        allowNullConstants = saved;
        return new CUBRIDCaseOperation(switchCondition, conditions, expressions, elseExpr);
    }

    // ----- aggregates and HAVING

    // The predicate of a HAVING clause over GROUP BY groupedColumns: its leaves are aggregates over any column, grouped
    // columns and constants.
    public CUBRIDExpression generateHavingPredicate(List<CUBRIDColumn> groupedColumns) {
        groupColumns = groupedColumns;
        allowAggregates = true;
        try {
            return generateExpression(CUBRIDDataType.BOOLEAN);
        } finally {
            groupColumns = null;
            allowAggregates = false;
        }
    }

    private CUBRIDExpression generateHavingLeaf(CUBRIDDataType type) {
        if (allowAggregates && Randomly.getBoolean()) {
            return generateAggregate(type);
        }
        List<CUBRIDColumn> grouped = groupColumns.stream().filter(c -> c.getType() == type)
                .collect(Collectors.toList());
        if (!grouped.isEmpty() && Randomly.getBoolean()) {
            return new CUBRIDColumnReference(Randomly.fromList(grouped));
        }
        return generateConstant(type);
    }

    // An aggregate of the given kind. No SUM of a DOUBLE: the order in which rows are added changes its last digits,
    // and the partitions add them in other orders.
    public CUBRIDExpression generateAggregate(CUBRIDDataType type) {
        List<CUBRIDColumn> savedGroupColumns = groupColumns;
        boolean savedAllowAggregates = allowAggregates;
        boolean savedAvoidCoalesce = avoidCoalesce;
        groupColumns = null; // the argument may name any column
        allowAggregates = false; // and holds no aggregate
        avoidCoalesce = CUBRIDBugs.derivedColumnOfFoldedNullCoalesce;
        try {
            switch (type) {
            case INT:
                if (Randomly.getBoolean()) {
                    CUBRIDExpression arg = Randomly.getBoolean() ? star() : generateExpression(getRandomType());
                    return new CUBRIDFunction<>(List.of(arg), CUBRIDAggregateFunction.COUNT);
                }
                return new CUBRIDFunction<>(List.of(generateExpression(CUBRIDDataType.INT)), Randomly.fromOptions(
                        CUBRIDAggregateFunction.SUM, CUBRIDAggregateFunction.MIN, CUBRIDAggregateFunction.MAX));
            case DOUBLE:
            case VARCHAR:
                return new CUBRIDFunction<>(List.of(generateExpression(type)),
                        Randomly.fromOptions(CUBRIDAggregateFunction.MIN, CUBRIDAggregateFunction.MAX));
            default:
                throw new AssertionError(type);
            }
        } finally {
            groupColumns = savedGroupColumns;
            allowAggregates = savedAllowAggregates;
            avoidCoalesce = savedAvoidCoalesce;
        }
    }

    private static CUBRIDExpression star() {
        return new CUBRIDColumnReference(new CUBRIDColumn("*", null));
    }

    // ----- constants and columns

    @Override
    public CUBRIDExpression generateConstant(CUBRIDDataType type) {
        // NULL is no logical expression to CUBRID: a predicate constant is TRUE or FALSE
        if (type != CUBRIDDataType.BOOLEAN && allowNullConstants && Randomly.getBooleanWithSmallProbability()) {
            return CUBRIDConstant.createNullConstant();
        }
        Randomly r = globalState.getRandomly();
        switch (type) {
        case BOOLEAN:
            return CUBRIDConstant.createBooleanConstant(Randomly.getBoolean());
        case INT:
            return CUBRIDConstant.createIntConstant(r.getInteger());
        case DOUBLE:
            return CUBRIDConstant.createDoubleConstant(getDouble(r));
        case VARCHAR:
            return CUBRIDConstant.createStringConstant(getString());
        default:
            throw new AssertionError(type);
        }
    }

    // a random string, without % where a debug server would read it as a printf conversion
    private String getString() {
        String value = globalState.getRandomly().getString();
        return avoidPercent() ? value.replace('%', '#') : value;
    }

    private boolean avoidPercent() {
        return CUBRIDBugs.debugXaslDumpUsesQueryAsFormat && globalState.getDbmsSpecificOptions().debugServer;
    }

    // A double CUBRID can read back as a literal, which a subnormal is not ("exceeds limit of double"). Integral
    // values half the time, so that a DOUBLE meets an INT with the same value.
    private static double getDouble(Randomly r) {
        double value = Randomly.getBoolean() ? r.getInteger() : r.getFiniteDouble();
        if (value != 0 && Math.abs(value) < Double.MIN_NORMAL) {
            return 0;
        }
        return value;
    }

    @Override
    protected CUBRIDExpression generateColumn(CUBRIDDataType type) {
        List<CUBRIDColumn> candidates = columns.stream().filter(c -> c.getType() == type).collect(Collectors.toList());
        return new CUBRIDColumnReference(Randomly.fromList(candidates));
    }

    // the kind of a column in play more often than not, so that comparisons are about the rows
    @Override
    protected CUBRIDDataType getRandomType() {
        if (!columns.isEmpty() && !Randomly.getBooleanWithRatherLowProbability()) {
            return Randomly.fromList(columns).getType();
        }
        return CUBRIDDataType.getRandomColumnType();
    }

    // a number meets a number of either kind (CUBRID converts), a string only a string
    private static CUBRIDDataType comparableType(CUBRIDDataType type) {
        if (type.isNumeric()) {
            return Randomly.fromOptions(CUBRIDDataType.INT, CUBRIDDataType.DOUBLE);
        }
        return type;
    }

    @Override
    protected boolean canGenerateColumnOfType(CUBRIDDataType type) {
        return columns.stream().anyMatch(c -> c.getType() == type);
    }

    // ----- TLP WHERE and NoREC

    @Override
    public CUBRIDExpression generatePredicate() {
        return generateExpression(CUBRIDDataType.BOOLEAN);
    }

    @Override
    public CUBRIDExpression negatePredicate(CUBRIDExpression predicate) {
        return new CUBRIDUnaryPrefixOperation(predicate, CUBRIDUnaryPrefixOperator.NOT);
    }

    @Override
    public CUBRIDExpression isNull(CUBRIDExpression expr) {
        return new CUBRIDUnaryPostfixOperation(expr, CUBRIDPostfixOperator.IS_NULL);
    }

    // the oracles call this first for every check: what the previous query held back is reset
    @Override
    public CUBRIDExpressionGenerator setTablesAndColumns(AbstractTables<CUBRIDTable, CUBRIDColumn> targetTables) {
        this.columns = targetTables.getColumns();
        this.tables = targetTables.getTables();
        this.avoidNullableShapes = false;
        this.subqueryCount = 0;
        return this;
    }

    @Override
    public CUBRIDExpression generateBooleanExpression() {
        return generateExpression(CUBRIDDataType.BOOLEAN);
    }

    @Override
    public CUBRIDSelect generateSelect() {
        return new CUBRIDSelect();
    }

    @Override
    public List<CUBRIDJoin> getRandomJoinClauses() {
        if (!globalState.getDbmsSpecificOptions().testJoins) {
            return Collections.emptyList();
        }
        List<CUBRIDTableReference> tableList = tables.stream().map(CUBRIDTableReference::new)
                .collect(Collectors.toList());
        List<CUBRIDJoin> joins = CUBRIDJoin.getJoins(tableList, globalState);
        tables = tableList.stream().map(t -> t.getTable()).collect(Collectors.toList());
        if (CUBRIDBugs.outerJoinReducedByNullablePredicate) {
            // The WHERE names no column of a table an outer join fills with NULL: TLP negates the WHERE, and the
            // reduction takes NOT (a AND b) for a term that rejects those NULL rows whatever forms the WHERE avoids.
            Set<CUBRIDTable> nullFilled = new HashSet<>();
            for (CUBRIDJoin join : joins) {
                if (join.getJoinType() == JoinType.LEFT) {
                    nullFilled.add(join.getRightTable().getTable());
                } else if (join.getJoinType() == JoinType.RIGHT) {
                    nullFilled.add(join.getLeftTable().getTable());
                }
            }
            if (!nullFilled.isEmpty()) {
                columns = columns.stream().filter(c -> !nullFilled.contains(c.getTable())).collect(Collectors.toList());
            }
        }
        return joins;
    }

    @Override
    public List<CUBRIDExpression> getTableRefs() {
        return tables.stream().map(CUBRIDTableReference::new).collect(Collectors.toList());
    }

    @Override
    public List<CUBRIDExpression> generateFetchColumns(boolean shouldCreateDummy) {
        if (shouldCreateDummy && Randomly.getBoolean()) {
            return List.of(star());
        }
        return Randomly.nonEmptySubset(columns).stream().map(CUBRIDColumnReference::new).collect(Collectors.toList());
    }

    // A GROUP BY over an index skips the sort as an ORDER BY does, on the same proof that a key column is not NULL.
    public void prepareForGroupBy() {
        if (CUBRIDBugs.orderBySkipOnIsFalse) {
            avoidNullableShapes = true;
        }
    }

    // Column references only: an integer constant in ORDER BY is a column position in CUBRID. The WHERE that comes
    // after it leaves out what the ORDER BY skip gets wrong.
    @Override
    public List<CUBRIDExpression> generateOrderBys() {
        if (CUBRIDBugs.orderBySkipOnIsFalse) {
            avoidNullableShapes = true;
        }
        return Randomly.nonEmptySubset(columns).stream()
                .map(c -> new CUBRIDOrderingTerm(new CUBRIDColumnReference(c), Ordering.getRandom()))
                .collect(Collectors.toList());
    }

    @Override
    public String generateOptimizedQueryString(CUBRIDSelect select, CUBRIDExpression whereCondition,
            boolean shouldUseAggregate) {
        if (shouldUseAggregate) {
            select.setFetchColumns(List.of(new CUBRIDFunction<>(List.of(star()), CUBRIDAggregateFunction.COUNT)));
        } else {
            select.setFetchColumns(columns.stream().map(CUBRIDColumnReference::new).collect(Collectors.toList()));
        }
        select.setWhereClause(whereCondition);
        return select.asString();
    }

    // CASE WHEN p THEN 1 ELSE 0 END counts the rows where p is TRUE, and the optimizer cannot use p to filter them
    @Override
    public String generateUnoptimizedQueryString(CUBRIDSelect select, CUBRIDExpression whereCondition) {
        CUBRIDExpression counted = new CUBRIDSearchedCase(whereCondition, CUBRIDConstant.createIntConstant(1),
                CUBRIDConstant.createIntConstant(0));
        select.setFetchColumns(List.of(new CUBRIDAlias(counted, "ref0")));
        select.setWhereClause(null);
        return "SELECT SUM(ref0) FROM (" + select.asString() + ") res";
    }

    public enum CUBRIDComparisonOperator implements Operator {
        EQUALS("="), NOT_EQUALS("<>"), BANG_EQUALS("!="), LESS("<"), LESS_EQUALS("<="), GREATER(">"),
        GREATER_EQUALS(">="), NULL_SAFE_EQUALS("<=>");

        private final String textRepresentation;

        CUBRIDComparisonOperator(String textRepresentation) {
            this.textRepresentation = textRepresentation;
        }

        @Override
        public String getTextRepresentation() {
            return textRepresentation;
        }

        public static CUBRIDComparisonOperator getRandom() {
            return Randomly.fromOptions(values());
        }
    }

    public enum CUBRIDLogicalOperator implements Operator {
        AND, OR;

        @Override
        public String getTextRepresentation() {
            return toString();
        }

        public static CUBRIDLogicalOperator getRandom() {
            return Randomly.fromOptions(values());
        }
    }

    public enum CUBRIDLikeOperator implements Operator {
        LIKE("LIKE"), NOT_LIKE("NOT LIKE");

        private final String textRepresentation;

        CUBRIDLikeOperator(String textRepresentation) {
            this.textRepresentation = textRepresentation;
        }

        @Override
        public String getTextRepresentation() {
            return textRepresentation;
        }

        public static CUBRIDLikeOperator getRandom() {
            return Randomly.fromOptions(values());
        }
    }

    public enum CUBRIDArithmeticOperator implements Operator {
        ADD("+"), SUB("-"), MUL("*"), DIV("/"), MOD("%");

        private final String textRepresentation;

        CUBRIDArithmeticOperator(String textRepresentation) {
            this.textRepresentation = textRepresentation;
        }

        @Override
        public String getTextRepresentation() {
            return textRepresentation;
        }
    }

    public enum CUBRIDStringOperator implements Operator {
        CONCAT("||");

        private final String textRepresentation;

        CUBRIDStringOperator(String textRepresentation) {
            this.textRepresentation = textRepresentation;
        }

        @Override
        public String getTextRepresentation() {
            return textRepresentation;
        }
    }

    public enum CUBRIDUnaryPrefixOperator implements Operator {
        NOT("NOT"), PLUS("+"), MINUS("-");

        private final String textRepresentation;

        CUBRIDUnaryPrefixOperator(String textRepresentation) {
            this.textRepresentation = textRepresentation;
        }

        @Override
        public String getTextRepresentation() {
            return textRepresentation;
        }
    }

    public enum CUBRIDPostfixOperator implements Operator {
        IS_NULL("IS NULL"), IS_NOT_NULL("IS NOT NULL"), IS_TRUE("IS TRUE"), IS_NOT_TRUE("IS NOT TRUE"),
        IS_FALSE("IS FALSE"), IS_NOT_FALSE("IS NOT FALSE"), IS_UNKNOWN("IS UNKNOWN"), IS_NOT_UNKNOWN("IS NOT UNKNOWN");

        private final String textRepresentation;

        CUBRIDPostfixOperator(String textRepresentation) {
            this.textRepresentation = textRepresentation;
        }

        @Override
        public String getTextRepresentation() {
            return textRepresentation;
        }
    }

    // CHAR_LENGTH takes a string and SUBSTR a string and two integers: the generator builds those two itself
    public enum CUBRIDFunc {
        ABS(1), SIGN(1), FLOOR(1), CEIL(1), ROUND(1), COALESCE(2), NULLIF(2), GREATEST(2), LEAST(2), UPPER(1), LOWER(1),
        TRIM(1), LTRIM(1), RTRIM(1), REVERSE(1), CHAR_LENGTH(1), SUBSTR(3);

        private final int nrArgs;

        CUBRIDFunc(int nrArgs) {
            this.nrArgs = nrArgs;
        }

        public int getNrArgs() {
            return nrArgs;
        }

        public static List<CUBRIDFunc> getFunctions(CUBRIDDataType type) {
            switch (type) {
            case INT:
                return List.of(ABS, SIGN, COALESCE, NULLIF, GREATEST, LEAST);
            case DOUBLE:
                return List.of(ABS, FLOOR, CEIL, ROUND, COALESCE, NULLIF, GREATEST, LEAST);
            case VARCHAR:
                return List.of(UPPER, LOWER, TRIM, LTRIM, RTRIM, REVERSE, COALESCE, NULLIF, GREATEST, LEAST);
            default:
                throw new AssertionError(type);
            }
        }
    }

    public enum CUBRIDAggregateFunction {
        COUNT, SUM, MIN, MAX
    }

}
