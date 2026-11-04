package sqlancer.cubrid;

import java.util.ArrayList;
import java.util.List;

import sqlancer.common.query.ExpectedErrors;

public final class CUBRIDErrors {

    private CUBRIDErrors() {
    }

    // what a query may raise on values the generator does not steer clear of
    public static List<String> getExpressionErrors() {
        List<String> errors = new ArrayList<>();
        errors.add("Cannot coerce"); // a value outside the range of the type it is converted to
        errors.add("Overflow occurred"); // integer arithmetic
        errors.add("Data overflow"); // CAST
        errors.add("Attempt to divide by zero");
        errors.add("exceeds limit of"); // a literal out of range, a subnormal double among them
        if (CUBRIDBugs.setOperationFoldedToNull) {
            errors.add("The query is not applicable to the executeQuery()");
        }
        if (CUBRIDBugs.nullSafeEqualsOfTwoMinuses) {
            errors.add("'unknown opcode' operator is not defined");
        }
        if (CUBRIDBugs.inListCoercedToFixedLengthElement) {
            errors.add("Element value is not within the domain of the set");
        }
        return errors;
    }

    public static void addExpressionErrors(ExpectedErrors errors) {
        errors.addAll(getExpressionErrors());
    }

    public static List<String> getInsertErrors() {
        List<String> errors = new ArrayList<>();
        errors.add("unique constraint violations");
        errors.add("violated NOT NULL constraint");
        errors.add("Missing value for attribute"); // a NOT NULL column left out of the column list
        return errors;
    }

    public static List<String> getIndexErrors() {
        List<String> errors = new ArrayList<>();
        errors.add("already defined"); // another index, or a PRIMARY KEY or UNIQUE constraint, on the same columns
        return errors;
    }

    public static void addIndexErrors(ExpectedErrors errors) {
        errors.addAll(getIndexErrors());
    }

    public static void addInsertErrors(ExpectedErrors errors) {
        errors.addAll(getInsertErrors());
    }

}
