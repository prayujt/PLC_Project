package plc.project;

import java.io.PrintWriter;
import java.util.Optional;

public final class Generator implements Ast.Visitor<Void> {

    private final PrintWriter writer;
    private int indent = 0;

    public Generator(PrintWriter writer) {
        this.writer = writer;
    }

    private void print(Object... objects) {
        for (Object object : objects) {
            if (object instanceof Ast) {
                visit((Ast) object);
            } else {
                writer.write(object.toString());
            }
        }
    }

    private void newline(int indent) {
        writer.println();
        for (int i = 0; i < indent; i++) {
            writer.write("    ");
        }
    }

    @Override
    public Void visit(Ast.Source ast) {
        print("public class Main {");
        newline(0);
        indent++;
        newline(indent);

        for (Ast.Global global : ast.getGlobals()) {
            visit(global);
            newline(indent);
        }

        if (ast.getGlobals().size() > 0) newline(indent);
        print("public static void main(String[] args) {");
        indent++;
        newline(indent);
        print("System.exit(new Main().main());");
        indent--;
        newline(indent);
        print("}");

        newline(0);
        newline(indent);

        for (int i = 0; i < ast.getFunctions().size(); i++) {
            visit(ast.getFunctions().get(i));
            if (i != ast.getFunctions().size() - 1) newline(indent);
        }

        indent--;
        newline(indent);
        print("}");
        return null;
    }

    @Override
    public Void visit(Ast.Global ast) {
        if (!ast.getMutable()) print("final ");
        print(ast.getVariable().getType().getJvmName());

        Optional<Ast.Expression> value = ast.getValue();
        if (value.isPresent() && value.get() instanceof Ast.Expression.PlcList) print("[]");
        print(" ", ast.getName());
        if (value.isPresent()) print(" = ", value.get());
        print(";");
        return null;
    }

    @Override
    public Void visit(Ast.Function ast) {
        print(ast.getFunction().getReturnType().getJvmName(), " ", ast.getName(), "(");
        for (int i = 0; i < ast.getParameters().size(); i++) {
            String parameterName = ast.getParameters().get(i);
            String parameterType = Environment.getType(ast.getParameterTypeNames().get(i)).getJvmName();
            print(parameterType, " ", parameterName);
            if (i != ast.getParameters().size() - 1) print(", ");
        }
        print(") {");

        if (ast.getStatements().size() == 0) {
            print("}");
            return null;
        }

        indent++;
        newline(indent);

        for (int i = 0; i < ast.getStatements().size(); i++) {
            print(ast.getStatements().get(i));
            if (i != ast.getStatements().size() - 1) newline(indent);
        }

        indent--;
        newline(indent);
        print("}");

        newline(0);
        return null;
    }

    @Override
    public Void visit(Ast.Statement.Expression ast) {
        print(ast.getExpression(), ";");
        return null;
    }

    @Override
    public Void visit(Ast.Statement.Declaration ast) {
        print(ast.getVariable().getType().getJvmName());

        Optional<Ast.Expression> value = ast.getValue();
        // if (value.isPresent() && value.get() instanceof Ast.Expression.PlcList) print("[]");
        print(" ", ast.getName());
        if (value.isPresent()) print(" = ", value.get());
        print(";");

        return null;
    }

    @Override
    public Void visit(Ast.Statement.Assignment ast) {
        print(ast.getReceiver(), " = ", ast.getValue(), ";");
        return null;
    }

    @Override
    public Void visit(Ast.Statement.If ast) {
        print("if (", ast.getCondition(), ") {");
        indent++;
        newline(indent);

        for (int i = 0; i < ast.getThenStatements().size(); i++) {
            print(ast.getThenStatements().get(i));
            if (i != ast.getThenStatements().size() - 1) newline(indent);
        }

        indent--;
        newline(indent);
        print("}");

        if (ast.getElseStatements().size() > 0) {
            print(" else {");
            indent++;
            newline(indent);

            for (int i = 0; i < ast.getElseStatements().size(); i++) {
                print(ast.getElseStatements().get(i));
                if (i != ast.getElseStatements().size() - 1) newline(indent);
            }

            indent--;
            newline(indent);
            print("}");
        }
        return null;
    }

    @Override
    public Void visit(Ast.Statement.Switch ast) {
        print("switch (", ast.getCondition(), ") {");
        indent++;
        newline(indent);

        for (int i = 0; i < ast.getCases().size(); i++) {
            print(ast.getCases().get(i));
            if (i != ast.getCases().size() - 1) newline(indent);
        }

        indent--;
        newline(indent);
        print("}");
        return null;
    }

    @Override
    public Void visit(Ast.Statement.Case ast) {
        if (ast.getValue().isPresent()) print("case ", ast.getValue().get(), ":");
        else print("default:");
        indent++;
        newline(indent);

        for (int i = 0; i < ast.getStatements().size(); i++) {
            print(ast.getStatements().get(i));
            if (i != ast.getStatements().size() - 1 || ast.getValue().isPresent()) newline(indent);
        }
        if (ast.getValue().isPresent()) print("break;");

        indent--;
        return null;
    }

    @Override
    public Void visit(Ast.Statement.While ast) {
        print("while (", ast.getCondition(), ") {");
        indent++;
        newline(indent);

        for (int i = 0; i < ast.getStatements().size(); i++) {
            print(ast.getStatements().get(i));
            if (i != ast.getStatements().size() - 1) newline(indent);
        }

        indent--;
        newline(indent);

        print("}");
        return null;
    }

    @Override
    public Void visit(Ast.Statement.Return ast) {
        print("return ", ast.getValue(), ";");
        return null;
    }

    @Override
    public Void visit(Ast.Expression.Literal ast) {
        Object literal = ast.getLiteral();
        if (literal instanceof String) print("\"", literal.toString(), "\"");
        else if (literal instanceof Character) print("\'", literal.toString(), "\'");
        else print(literal.toString());
        return null;
    }

    @Override
    public Void visit(Ast.Expression.Group ast) {
        print("(", ast.getExpression(), ")");
        return null;
    }

    @Override
    public Void visit(Ast.Expression.Binary ast) {
        if (ast.getOperator().equals("^")) {
            print("Math.pow(", ast.getLeft(), ", ", ast.getRight(), ")");
        }
        else print(ast.getLeft(), " ", ast.getOperator(), " ", ast.getRight());
        return null;
    }

    @Override
    public Void visit(Ast.Expression.Access ast) {
        print(ast.getName());
        if (ast.getOffset().isPresent()) print("[", ast.getOffset().get(), "]");
        return null;
    }

    @Override
    public Void visit(Ast.Expression.Function ast) {
        print(ast.getFunction().getJvmName(), "(");

        for (int i = 0; i < ast.getArguments().size(); i++) {
            print(ast.getArguments().get(i));
            if (i != ast.getArguments().size() - 1) print(", ");
        }

        print(")");
        return null;
    }

    @Override
    public Void visit(Ast.Expression.PlcList ast) {
        print("{");
        for (int i = 0; i < ast.getValues().size(); i++) {
            print(ast.getValues().get(i));
            if (i != ast.getValues().size() - 1) print(", ");
        }
        print("}");
        return null;
    }

}
