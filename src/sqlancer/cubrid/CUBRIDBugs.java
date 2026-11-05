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
    // or IS FALSE over an OR, nor NOT over an AND whose other operand is false. The NULL-extended rows are lost. With
    // an
    // outer join in the query, the WHERE names no column of a table the outer join fills with NULL.
    // SELECT COUNT(*) FROM o LEFT OUTER JOIN i ON i.a = -1 WHERE 0 IN (0, i.a)
    public static boolean outerJoinReducedByNullablePredicate = true;

    // ORDER BY is skipped through a composite index although a row whose key is all NULL is not in it: the WHERE
    // term (c IS NOT NULL) IS FALSE is taken for proof that c is not NULL, and so is a NOT BETWEEN whose bound is c
    // (since 10.2 at least). A GROUP BY over the index skips its sort the same way. With an ORDER BY or a GROUP BY in
    // the query, the WHERE leaves out the same forms as with an outer join.
    // SELECT b FROM t WHERE (a IS NOT NULL) IS FALSE ORDER BY b, a DESC -- with an index on (b, a DESC)
    // SELECT c, COUNT(*) FROM t WHERE 10 NOT BETWEEN c AND -5 GROUP BY c -- with an index on c: no NULL group
    // NULLIF is missing from that list too (since 11.4.0): WHERE NULLIF(5, c + c) IS NOT NULL GROUP BY c loses it as
    // well.
    public static boolean orderBySkipOnIsFalse = true;

    // A comparison of two unary minus operands, -x op -y, is rewritten to x op' y with the converse operator, which
    // <=> does not have: the operator becomes 0 and type checking fails with "'unknown opcode' operator is not
    // defined" (a debug client asserts in pt_show_binopcode () instead). The generator uses = for <=> when both
    // operands hold a unary minus anywhere, since +(-x) and a CASE that folds to -x reach the same rewrite.
    // SELECT 1 FROM t WHERE (- a) <=> (- a)
    public static boolean nullSafeEqualsOfTwoMinuses = true;

    // An IN list is coerced to the domain of an element of fixed length (a VARCHAR(2) column, or a folded LOWER('' ||
    // 'ab')), so a longer literal in the list raises an error instead of comparing unequal.
    // SELECT 1 FROM s WHERE 'xyz' IN (v, 'xyz') -- v VARCHAR(2): Cannot coerce 'xyz' to type varchar.
    public static boolean inListCoercedToFixedLengthElement = true;

    // Folding a constant-false WHEN of a simple CASE whose result is a NUMERIC, with a THEN NULL branch after it,
    // dereferences a NULL cast type in pt_fold_const_expr (): the client (csql, or the broker's CAS) dies of SIGSEGV,
    // which the JDBC driver reports as "Request timed out". CBRD-27948.
    // SELECT CASE 'q' WHEN c THEN 1.5 WHEN 'z' THEN 1 WHEN c THEN NULL ELSE 2 END FROM (SELECT 'a' AS c FROM db_root) x
    public static boolean caseFoldWithNullBranchCrash = true;

    // A LIKE pattern that is not folded at compile time (an expression, a host variable) gets a range term
    // c >= like_match_lower_bound (p) AND c < like_match_upper_bound (p) that leaves out the empty string, so a pattern
    // that matches it ('' or '%') loses the rows where c = ''. A prepared c LIKE ? with '%' bound misses them too.
    // SELECT COUNT(*) FROM t WHERE c LIKE NULLIF('', CAST(1 AS VARCHAR)) -- t has rows c = ''
    public static boolean likeRuntimePatternMissesEmptyString = true;

    // In the WHERE clause, NOT of a BETWEEN that mixes integer and DOUBLE operands casts all three to DOUBLE, while the
    // BETWEEN itself compares the integers exactly: a BIGINT beyond 2^53, which a DOUBLE cannot tell from its neighbor,
    // satisfies neither p nor NOT p. (A FLOAT column compared with an INT makes p and NOT p both TRUE instead; FLOAT
    // columns are generated only with --test-float true.)
    // SELECT COUNT(*) FROM t WHERE NOT (c BETWEEN c + 2 AND 0.5E0) -- c BIGINT -9223372036854775808: 0, expected 1
    public static boolean betweenNegationCastsToDouble = true;

    // A debug server logs the XASL tree of a query that enters the parallel executor (a UNION ALL, for one) with the
    // query text as the printf format: a literal that stays in the text, such as a NOT LIKE pattern, with %n in it
    // takes the server down. Applies only with --debug-server true. CBRD-27955.
    // SELECT c FROM t WHERE c LIKE '%n%n' UNION ALL SELECT c FROM t WHERE c NOT LIKE '%n%n'
    public static boolean debugXaslDumpUsesQueryAsFormat = true;

    // A subquery whose WHERE folds to FALSE at compile time becomes a NULL value: ALL/ANY/SOME then fail with "'> all '
    // operator is not defined on types integer and null", and IN or EXISTS of it is NULL where it is FALSE, so that
    // IS FALSE or NOT around it loses the rows. Every leaf of a subquery WHERE is then a column.
    // SELECT 'x' FROM db_root WHERE 1 > ALL (SELECT a FROM t WHERE 1 = 2)
    // SELECT COUNT(*) FROM db_root WHERE (1 IN (SELECT 1 FROM db_root WHERE 1 = 2)) IS FALSE -- 0, expected 1
    public static boolean constantFalseSubqueryIsNullTyped = true;

    // An EXISTS of such a subquery folds to an integer but keeps the type NULL of the expression, so it prints as null
    // like a plain NULL: two statements that fold to the same text share one XASL cache entry, across sessions, and the
    // second one runs the plan of the first (CBRD-27957, every release since 10.2 at least).
    // SELECT (CASE WHEN ((EXISTS (SELECT 1 FROM t WHERE 'a' <> 'a')) IS FALSE) THEN 1 ELSE 0 END) FROM t; -- 1 per row
    // SELECT (CASE WHEN ((t.c <= NULL) IS FALSE) THEN 1 ELSE 0 END) FROM t; -- then 1 per row as well, expected 0
    public static boolean foldedFalseSubqueryInXaslCacheKey = true;

    // The parallel execution of UNION ALL branches crashes the server when a branch joins two tables and an IN subquery
    // in its WHERE names a column of the other table (CBRD-27959): a worker frees or writes a list file another one
    // uses. Under a join, a subquery is then an uncorrelated one.
    // SELECT o2.n FROM o1, o2 WHERE o2.n IN (SELECT i.n FROM i WHERE o1.s <> 'x') UNION ALL (the same SELECT)
    public static boolean parallelUnionCorrelatedSubqueryCrash = true;

    // A NOT EXISTS or NOT IN unnested into a nested-loop ANTI JOIN takes a WHERE term that reads no table (a constant
    // expression left for run time, an uncorrelated subquery, a host variable) into its inner scan: when the term is
    // false the inner side is empty and every outer row comes out (CBRD-27605). TLP negates the predicate, so any
    // correlated subquery becomes such a NOT EXISTS in one partition: subqueries are then uncorrelated ones, one per
    // predicate at most, since an uncorrelated NOT IN is an anti join too and another uncorrelated subquery is such a
    // term.
    // SELECT COUNT(*) FROM ta WHERE NOT EXISTS (SELECT 1 FROM tb WHERE tb.ca = ta.ca) AND (SELECT COUNT(*) FROM tc) > 5
    public static boolean antiJoinTakesNoTableTermIntoInnerScan = true;

    // xbtree_add_index () leaves the common prefix of the root header unset, so an index created on an empty table or
    // on rows whose keys are all NULL may say that its root leaf is compressed. A debug server asserts in
    // btree_node_get_common_prefix () once that leaf holds two keys of a multi-column index (CBRD-27963). Against a
    // debug server an index or a primary key then has one column.
    // CREATE TABLE t (a INT, b INT); INSERT INTO t VALUES (NULL, NULL), (NULL, NULL), (NULL, NULL);
    // CREATE INDEX i ON t (a, b); INSERT INTO t VALUES (1, NULL), (2, NULL), (3, NULL);
    public static boolean emptyIndexRootPrefixUnset = true;

    // A term of an outer join's ON that reads no table (a constant expression left for run time, a host variable) is
    // also applied above the join once another table is in the FROM list: a false one leaves no row at all instead of
    // NULL-filled ones (since 10.2 at least). The ON of an outer join then has columns only as leaves.
    // SELECT COUNT(*) FROM a, b LEFT OUTER JOIN c ON CAST(2 AS VARCHAR) = '1' -- 0, expected COUNT(a) * COUNT(b)
    public static boolean outerJoinOnTermWithoutTableFiltersAll = true;

    // A bound value as the select list of an EXISTS subquery fails with "Internal error- reporting semantic error" (or
    // "System error (generate var)") when the EXISTS is used as a value: under IS [NOT] NULL or in a select list. The
    // constants of an EXISTS subquery then stay literals in a prepared statement.
    // PREPARE s FROM 'SELECT * FROM t WHERE (EXISTS (SELECT ? FROM t)) IS NULL'; EXECUTE s USING 1;
    public static boolean existsSelectListHostVariable = true;

    // COALESCE (NVL, IFNULL and NVL2 too) whose later argument folds to NULL at compile time (CAST(NULL AS INT),
    // NULLIF(1, 1)) becomes a VARCHAR, while the column of a derived table over it is declared with the type the
    // expression had before folding: the server sends the string and a client reads it as an integer (CBRD-27962).
    // An aggregate argument then holds no COALESCE.
    // SELECT a FROM (SELECT COALESCE(c, CAST(NULL AS INT)) AS a FROM t) x -- 825373492 for c = 123456789
    public static boolean derivedColumnOfFoldedNullCoalesce = true;

    private CUBRIDBugs() {
    }

    // one column for an index or a primary key, against a debug server while emptyIndexRootPrefixUnset holds
    public static boolean singleColumnKeys(CUBRIDGlobalState globalState) {
        return emptyIndexRootPrefixUnset && globalState.getDbmsSpecificOptions().debugServer;
    }
}
