package sqlancer.cubrid.ast;

public class CUBRIDCastNode implements CUBRIDExpression {

    private final CUBRIDExpression expression;
    private final String typeName;

    public CUBRIDCastNode(CUBRIDExpression expression, String typeName) {
        this.expression = expression;
        this.typeName = typeName;
    }

    public CUBRIDExpression getExpression() {
        return expression;
    }

    public String getTypeName() {
        return typeName;
    }

}
