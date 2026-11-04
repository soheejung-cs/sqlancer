package sqlancer.cubrid;

// CUBRID defects SQLancer found, each with the switch that keeps the generators or the expected errors off it until
// it is fixed, so that a campaign does not report it again and again.
public final class CUBRIDBugs {

    // A set operation whose arms are all empty by a constant-false condition (or an INTERSECT with one such arm) is
    // folded into a NULL value: at the top the statement is no longer a query, so the JDBC driver refuses
    // executeQuery(), and under NOT IN or NOT EXISTS the NULL stands for the empty set and drops every row.
    // SELECT 1 FROM db_root WHERE 1 = 2 UNION ALL SELECT 1 FROM db_root WHERE 1 = 2
    public static boolean setOperationFoldedToNull = true;

    // An outer join is reduced to an inner join by a WHERE predicate that can still be TRUE when the columns of the
    // NULL-supplying table are NULL: a column of it in an IN list or in a bound of NOT BETWEEN, or under IS NOT NULL
    // or IS FALSE over an OR. The NULL-extended rows are lost.
    // SELECT COUNT(*) FROM o LEFT OUTER JOIN i ON i.a = -1 WHERE 0 IN (0, i.a)
    public static boolean outerJoinReducedByNullablePredicate = true;

    // ORDER BY is skipped through a composite index although a row whose key is all NULL is not in it: the WHERE
    // term (c IS NOT NULL) IS FALSE is taken for proof that c is not NULL.
    // SELECT b FROM t WHERE (a IS NOT NULL) IS FALSE ORDER BY b, a DESC -- with an index on (b, a DESC)
    public static boolean orderBySkipOnIsFalse = true;

    // A comparison of two unary minus operands, -x op -y, is rewritten to x op' y with the converse operator, which
    // <=> does not have: the operator becomes 0 and type checking fails with "'unknown opcode' operator is not
    // defined".
    // SELECT 1 FROM t WHERE (- a) <=> (- a)
    public static boolean nullSafeEqualsOfTwoMinuses = true;

    // An IN list is coerced to the domain of an element of fixed length (a VARCHAR(2) column, or a folded LOWER('' ||
    // 'ab')), so a longer literal in the list raises an error instead of comparing unequal.
    // SELECT 1 FROM s WHERE 'xyz' IN (v, 'xyz') -- v VARCHAR(2): Cannot coerce 'xyz' to type varchar.
    public static boolean inListCoercedToFixedLengthElement = true;

    // Folding a constant-false WHEN of a simple CASE whose result is a NUMERIC, with a THEN NULL branch after it,
    // dereferences a NULL cast type in pt_fold_const_expr (): the client (csql, or the broker's CAS) dies of SIGSEGV,
    // which the JDBC driver reports as "Request timed out".
    // SELECT CASE 'q' WHEN c THEN 1.5 WHEN 'z' THEN 1 WHEN c THEN NULL ELSE 2 END FROM (SELECT 'a' AS c FROM db_root) x
    public static boolean caseFoldWithNullBranchCrash = true;

    // A LIKE pattern that is not folded at compile time (an expression, a host variable) gets a range term
    // c >= like_match_lower_bound (p) AND c < like_match_upper_bound (p) that leaves out the empty string, so a pattern
    // that matches it ('' or '%') loses the rows where c = ''. A prepared c LIKE ? with '%' bound misses them too.
    // SELECT COUNT(*) FROM t WHERE c LIKE NULLIF('', CAST(1 AS VARCHAR)) -- t has rows c = ''
    public static boolean likeRuntimePatternMissesEmptyString = true;

    private CUBRIDBugs() {
    }

}
