package sqlancer.cubrid.ast;

public abstract class CUBRIDConstant implements CUBRIDExpression {

    // the value a prepared statement binds for this constant, or null where the literal stays in the text
    public Object getBindValue() {
        return null;
    }

    public static class CUBRIDNullConstant extends CUBRIDConstant {

        @Override
        public String toString() {
            return "NULL";
        }

    }

    public static class CUBRIDIntConstant extends CUBRIDConstant {

        private final long value;

        public CUBRIDIntConstant(long value) {
            this.value = value;
        }

        @Override
        public Object getBindValue() {
            return value;
        }

        @Override
        public String toString() {
            return String.valueOf(value);
        }

    }

    public static class CUBRIDDoubleConstant extends CUBRIDConstant {

        private final double value;

        public CUBRIDDoubleConstant(double value) {
            this.value = value;
        }

        @Override
        public Object getBindValue() {
            return value;
        }

        @Override
        public String toString() {
            // without an exponent the literal is a NUMERIC in CUBRID (1.5 is NUMERIC(2,1)), with one a DOUBLE
            String text = String.valueOf(value);
            return text.contains("E") ? text : text + "E0";
        }

    }

    public static class CUBRIDStringConstant extends CUBRIDConstant {

        private final String value;

        public CUBRIDStringConstant(String value) {
            this.value = value;
        }

        @Override
        public Object getBindValue() {
            return value;
        }

        @Override
        public String toString() {
            return "'" + value.replace("'", "''") + "'";
        }

    }

    public static class CUBRIDBooleanConstant extends CUBRIDConstant {

        private final boolean value;

        public CUBRIDBooleanConstant(boolean value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return value ? "TRUE" : "FALSE";
        }

    }

    public static CUBRIDExpression createNullConstant() {
        return new CUBRIDNullConstant();
    }

    public static CUBRIDExpression createIntConstant(long value) {
        return new CUBRIDIntConstant(value);
    }

    public static CUBRIDExpression createDoubleConstant(double value) {
        return new CUBRIDDoubleConstant(value);
    }

    public static CUBRIDExpression createStringConstant(String value) {
        return new CUBRIDStringConstant(value);
    }

    public static CUBRIDExpression createBooleanConstant(boolean value) {
        return new CUBRIDBooleanConstant(value);
    }

}
