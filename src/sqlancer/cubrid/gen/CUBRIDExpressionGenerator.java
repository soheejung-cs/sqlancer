package sqlancer.cubrid.gen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.Randomly;
import sqlancer.common.ast.BinaryOperatorNode.Operator;
import sqlancer.common.ast.newast.NewOrderingTerm.Ordering;
import sqlancer.common.gen.TLPWhereGenerator;
import sqlancer.common.gen.TypedExpressionGenerator;
import sqlancer.common.schema.AbstractTables;
import sqlancer.cubrid.CUBRIDBugs;
import sqlancer.cubrid.CUBRIDGlobalState;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDColumn;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDDataType;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDTable;
import sqlancer.cubrid.ast.CUBRIDBetweenOperation;
import sqlancer.cubrid.ast.CUBRIDBinaryOperation;
import sqlancer.cubrid.ast.CUBRIDCaseOperation;
import sqlancer.cubrid.ast.CUBRIDCastNode;
import sqlancer.cubrid.ast.CUBRIDColumnReference;
import sqlancer.cubrid.ast.CUBRIDConstant;
import sqlancer.cubrid.ast.CUBRIDExpression;
import sqlancer.cubrid.ast.CUBRIDFunction;
import sqlancer.cubrid.ast.CUBRIDInOperation;
import sqlancer.cubrid.ast.CUBRIDJoin;
import sqlancer.cubrid.ast.CUBRIDOrderingTerm;
import sqlancer.cubrid.ast.CUBRIDSelect;
import sqlancer.cubrid.ast.CUBRIDTableReference;
import sqlancer.cubrid.ast.CUBRIDUnaryPostfixOperation;
import sqlancer.cubrid.ast.CUBRIDUnaryPrefixOperation;

// Generates expressions of a given kind (CUBRIDDataType), so that a predicate stands only where CUBRID wants a logical
// expression and a string only where it wants a string: an operand CUBRID cannot take costs the query, not a bug.
public class CUBRIDExpressionGenerator extends TypedExpressionGenerator<CUBRIDExpression, CUBRIDColumn, CUBRIDDataType>
        implements TLPWhereGenerator<CUBRIDSelect, CUBRIDJoin, CUBRIDExpression, CUBRIDTable, CUBRIDColumn> {

    private final CUBRIDGlobalState globalState;
    private List<CUBRIDTable> tables = Collections.emptyList();
    private boolean allowNullConstants = true;

    public CUBRIDExpressionGenerator(CUBRIDGlobalState globalState) {
        this.globalState = globalState;
    }

    private enum BooleanExpression {
        NOT, AND_OR, COMPARISON, IS_NULL, IS_TRUTH, BETWEEN, IN, LIKE
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
            return super.generateLeafNode(type);
        }
        if (Randomly.getBooleanWithSmallProbability()) {
            return CUBRIDConstant.createBooleanConstant(Randomly.getBoolean());
        }
        CUBRIDDataType left = getRandomType();
        return new CUBRIDBinaryOperation(super.generateLeafNode(left), super.generateLeafNode(comparableType(left)),
                CUBRIDComparisonOperator.getRandom());
    }

    private CUBRIDExpression generateBooleanExpression(int depth) {
        CUBRIDDataType type;
        switch (Randomly.fromOptions(BooleanExpression.values())) {
        case NOT:
            return new CUBRIDUnaryPrefixOperation(generateExpression(CUBRIDDataType.BOOLEAN, depth + 1),
                    CUBRIDUnaryPrefixOperator.NOT);
        case AND_OR:
            return new CUBRIDBinaryOperation(generateExpression(CUBRIDDataType.BOOLEAN, depth + 1),
                    generateExpression(CUBRIDDataType.BOOLEAN, depth + 1), CUBRIDLogicalOperator.getRandom());
        case COMPARISON:
            type = getRandomType();
            return new CUBRIDBinaryOperation(generateExpression(type, depth + 1),
                    generateExpression(comparableType(type), depth + 1), CUBRIDComparisonOperator.getRandom());
        case IS_NULL:
            return new CUBRIDUnaryPostfixOperation(
                    generateExpression(Randomly.fromOptions(CUBRIDDataType.values()), depth + 1),
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
        default:
            throw new AssertionError();
        }
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
        CUBRIDFunc func = Randomly.fromList(CUBRIDFunc.getFunctions(type));
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

    @Override
    public CUBRIDExpressionGenerator setTablesAndColumns(AbstractTables<CUBRIDTable, CUBRIDColumn> targetTables) {
        this.columns = targetTables.getColumns();
        this.tables = targetTables.getTables();
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
        return joins;
    }

    @Override
    public List<CUBRIDExpression> getTableRefs() {
        return tables.stream().map(CUBRIDTableReference::new).collect(Collectors.toList());
    }

    @Override
    public List<CUBRIDExpression> generateFetchColumns(boolean shouldCreateDummy) {
        if (shouldCreateDummy && Randomly.getBoolean()) {
            return List.of(new CUBRIDColumnReference(new CUBRIDColumn("*", null)));
        }
        return Randomly.nonEmptySubset(columns).stream().map(CUBRIDColumnReference::new).collect(Collectors.toList());
    }

    // column references only: an integer constant in ORDER BY is a column position in CUBRID
    @Override
    public List<CUBRIDExpression> generateOrderBys() {
        if (CUBRIDBugs.orderBySkipOnIsFalse) {
            return Collections.emptyList();
        }
        return Randomly.nonEmptySubset(columns).stream()
                .map(c -> new CUBRIDOrderingTerm(new CUBRIDColumnReference(c), Ordering.getRandom()))
                .collect(Collectors.toList());
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

}
