package plc.project;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;

/**
 * See the specification for information about what the different visit
 * methods should do.
 */

public final class Analyzer implements Ast.Visitor<Void> {

    public Scope scope;
    private Ast.Function function;

    private static HashMap<String, HashSet<String>> types = new HashMap<String, HashSet<String>>() {{
        put("Any", new HashSet<String>(Arrays.asList("Any", "Comparable", "Boolean", "Integer", "Decimal", "Character", "String")));
        put("Nil", new HashSet<String>(Arrays.asList("Nil")));
        put("Comparable", new HashSet<String>(Arrays.asList("Comparable", "Integer", "Decimal", "Character", "String")));
    }};

    public Analyzer(Scope parent) {
        scope = new Scope(parent);
        scope.defineFunction("print", "System.out.println", Arrays.asList(Environment.Type.ANY), Environment.Type.NIL, args -> Environment.NIL);
    }

    public Scope getScope() {
        return scope;
    }

    @Override
    public Void visit(Ast.Source ast) {
        for (Ast.Global global : ast.getGlobals()) {
            visit(global);
        }

        boolean mainFound = false;
        for (Ast.Function function : ast.getFunctions()) {
            if (function.getName() == "main" && function.getParameters().size() == 0) {
                mainFound = true;
                if (!function.getReturnTypeName().isPresent() || function.getReturnTypeName().get() != "Integer")
                    throw new RuntimeException("Main function does not return Integer!");
            }
            visit(function);
        }
        if (!mainFound) throw new RuntimeException("main/0 function not found!");
        return null;
    }

    @Override
    public Void visit(Ast.Global ast) {
        if (ast.getValue().isPresent()) {
            Ast.Expression expression = ast.getValue().get();
            visit(expression);
            requireAssignable(Environment.getType(ast.getTypeName()), expression.getType()); // set type of declaration
        }
        Environment.Variable variable = scope.defineVariable(ast.getName(), ast.getName(), Environment.getType(ast.getTypeName()), ast.getMutable(), Environment.NIL);

        ast.setVariable(variable);

        return null;
    }

    @Override
    public Void visit(Ast.Function ast) {
        List<Environment.Type> parameterTypes = new ArrayList<Environment.Type>();
        for (String type : ast.getParameterTypeNames()) {
            parameterTypes.add(Environment.getType(type));
        }

        Environment.Type expectedReturnType = ast.getReturnTypeName().isPresent() ? Environment.getType(ast.getReturnTypeName().get()) : Environment.Type.NIL;
        Environment.Function function = scope.defineFunction(ast.getName(), ast.getName(), parameterTypes, expectedReturnType,
            (List<Environment.PlcObject> args) -> { return Environment.NIL; });
        scope = new Scope(scope);

        Environment.Type returnType = Environment.Type.NIL;
        for (Ast.Statement statement : ast.getStatements()) {
            visit(statement);
            if (statement instanceof Ast.Statement.Return) {
                requireAssignable(expectedReturnType, ((Ast.Statement.Return) statement).getValue().getType());
            }
        }

        ast.setFunction(function);
        scope = scope.getParent();
        return null;
    }

    @Override
    public Void visit(Ast.Statement.Expression ast) {
        Ast.Expression expression = ast.getExpression();
        if (!(expression instanceof Ast.Expression.Function)) throw new RuntimeException("Invalid statement!");

        visit(expression);
        return null;
    }

    @Override
    public Void visit(Ast.Statement.Declaration ast) {
        Environment.Type type = Environment.Type.NIL;
        if (ast.getValue().isPresent()) {
            Ast.Expression expression = ast.getValue().get();
            visit(expression);
            type = expression.getType();
        }
        if (ast.getTypeName().isPresent()) {
            if (type != Environment.Type.NIL) requireAssignable(Environment.getType(ast.getTypeName().get()), type); // set type of declaration
            else type = Environment.getType(ast.getTypeName().get());
        }
        if (type == Environment.Type.NIL) throw new RuntimeException("No types given!");
        Environment.Variable variable = scope.defineVariable(ast.getName(), ast.getName(), type, true, Environment.NIL);

        ast.setVariable(variable);

        return null;
    }

    @Override
    public Void visit(Ast.Statement.Assignment ast) {
        if (!(ast.getReceiver() instanceof Ast.Expression.Access)) throw new RuntimeException("Reciever not of type Ast.Expression.Access!");
        Ast.Expression.Access receiver = (Ast.Expression.Access) ast.getReceiver();
        visit(receiver);

        Ast.Expression value = ast.getValue();
        visit(value);
        requireAssignable(receiver.getType(), value.getType());

        return null;
    }

    @Override
    public Void visit(Ast.Statement.If ast) {
        Ast.Expression condition = ast.getCondition();
        visit(condition);
        if (condition.getType() != Environment.Type.BOOLEAN) throw new RuntimeException("If statement condition is not of type Boolean!");
        if (ast.getThenStatements().size() == 0) throw new RuntimeException("No then statements of if statement!");

        scope = new Scope(scope);
        for (Ast.Statement statement : ast.getThenStatements()) {
            visit(statement);
        }
        scope = scope.getParent();

        scope = new Scope(scope);
        for (Ast.Statement statement : ast.getElseStatements()) {
            visit(statement);
        }
        scope = scope.getParent();

        return null;
    }

    @Override
    public Void visit(Ast.Statement.Switch ast) {
        Ast.Expression condition = ast.getCondition();
        visit(condition);
        Environment.Type conditionType = condition.getType();

        List<Ast.Statement.Case> cases = ast.getCases();

        for (int i = 0; i < cases.size(); i++) {
            Optional<Ast.Expression> caseValue = cases.get(i).getValue();
            if (caseValue.isPresent()) {
                if (i == cases.size() - 1) throw new RuntimeException("Default case has a value!");
                Ast.Expression value = caseValue.get();
                visit(value);
                requireAssignable(conditionType, value.getType());
            }
            else if (i != cases.size() - 1) throw new RuntimeException("Missing case value!");
            visit(cases.get(i));
        }

        return null;
    }

    @Override
    public Void visit(Ast.Statement.Case ast) {
        scope = new Scope(scope);

        for (Ast.Statement statement : ast.getStatements()) {
            visit(statement);
        }

        scope = scope.getParent();
        return null;
    }

    @Override
    public Void visit(Ast.Statement.While ast) {
        Ast.Expression condition = ast.getCondition();
        visit(condition);
        if (condition.getType() != Environment.Type.BOOLEAN) throw new RuntimeException("While statement condition is not of type Boolean!");

        scope = new Scope(scope);

        for (Ast.Statement statement : ast.getStatements()) {
            visit(statement);
        }

        scope = scope.getParent();
        return null;
    }

    @Override
    public Void visit(Ast.Statement.Return ast) {
        Ast.Expression returnExpression = ast.getValue();
        visit(returnExpression);

        return null;
    }

    @Override
    public Void visit(Ast.Expression.Literal ast) {
        Object literal = ast.getLiteral();
        if (literal instanceof BigInteger) {
            BigInteger temp = (BigInteger) literal;
            if (temp.bitCount() > 32) throw new RuntimeException("Exceeding int range!");
            ast.setType(Environment.getType("Integer"));
        }
        else if (literal instanceof BigDecimal) {
            BigDecimal temp = (BigDecimal) literal;
            double temp2 = temp.doubleValue();
            ast.setType(Environment.getType("Decimal"));
        }
        else if (literal instanceof String) ast.setType(Environment.getType("String"));
        else if (literal instanceof Character) ast.setType(Environment.getType("Character"));
        else if (literal instanceof Boolean) ast.setType(Environment.getType("Boolean"));
        else ast.setType(Environment.getType("Nil"));

        return null;
    }

    @Override
    public Void visit(Ast.Expression.Group ast) {
        Ast.Expression expression = ast.getExpression();
        if (!(expression instanceof Ast.Expression.Binary)) throw new RuntimeException("Enclosed expression is not of type Ast.Expression.Binary!");
        visit(expression);
        return null;
    }

    @Override
    public Void visit(Ast.Expression.Binary ast) {
        String operator = ast.getOperator();
        Ast.Expression left = ast.getLeft();
        Ast.Expression right = ast.getRight();
        visit(left);
        visit(right);

        if (operator.equals("&&") || operator.equals("||")) {
            requireAssignable(Environment.Type.BOOLEAN, left.getType());
            requireAssignable(Environment.Type.BOOLEAN, right.getType());
            ast.setType(Environment.getType("Boolean"));
        }

        else if (operator.equals("<") || operator.equals(">") || operator.equals("==") || operator.equals("!=")) {
            requireAssignable(Environment.Type.COMPARABLE, left.getType());
            requireAssignable(Environment.Type.COMPARABLE, right.getType());
            if (left.getType() != right.getType()) throw new RuntimeException("Right and left sides of binary expression are not of same type!");
            ast.setType(Environment.getType("Boolean"));
        }

        else if (operator.equals("+")) {
            if (left.getType() == Environment.Type.STRING || right.getType() == Environment.Type.STRING) {
                ast.setType(Environment.getType("String"));
            }
            else if (left.getType() == Environment.Type.INTEGER) {
                requireAssignable(Environment.Type.INTEGER, right.getType());
                ast.setType(Environment.getType("Integer"));
            }
            else if (left.getType() == Environment.Type.DECIMAL) {
                requireAssignable(Environment.Type.DECIMAL, right.getType());
                ast.setType(Environment.getType("Decimal"));
            }
        }

        else if (operator.equals("-") || operator.equals("*") || operator.equals("/")) {
            if (left.getType() == Environment.Type.INTEGER) {
                requireAssignable(Environment.Type.INTEGER, right.getType());
                ast.setType(Environment.getType("Integer"));
            }
            else if (left.getType() == Environment.Type.DECIMAL) {
                requireAssignable(Environment.Type.DECIMAL, right.getType());
                ast.setType(Environment.getType("Decimal"));
            }
        }

        else if (operator.equals("^")) {
            requireAssignable(Environment.Type.INTEGER, left.getType());
            requireAssignable(Environment.Type.INTEGER, right.getType());
            ast.setType(Environment.getType("Integer"));
        }

        else throw new RuntimeException("Invalid operator!");

        return null;
    }

    @Override
    public Void visit(Ast.Expression.Access ast) {
        Optional<Ast.Expression> offset = ast.getOffset();
        if (offset.isPresent()) {
            Ast.Expression offsetExpression = offset.get();
            visit(offsetExpression);
            if (offsetExpression.getType() != Environment.Type.INTEGER) throw new RuntimeException("Offset is not of type Integer!");
        }
        ast.setVariable(scope.lookupVariable(ast.getName()));
        return null;
    }

    @Override
    public Void visit(Ast.Expression.Function ast) {
        Environment.Function function = scope.lookupFunction(ast.getName(), ast.getArguments().size());

        List<Environment.Type> types = function.getParameterTypes();
        List<Ast.Expression> arguments = ast.getArguments();

        for (int i = 0; i < arguments.size(); i++) {
            Ast.Expression argument = arguments.get(i);
            visit(argument);
            requireAssignable(types.get(i), argument.getType());
        }

        ast.setFunction(function);
        return null;
    }

    @Override
    public Void visit(Ast.Expression.PlcList ast) {
        Environment.Type type = Environment.Type.ANY;
        for (Ast.Expression value : ast.getValues()) {
            visit(value);
            if (type == Environment.Type.ANY) type = value.getType();
            // requireAssignable(ast.getType(), value.getType());
        }
        ast.setType(type);

        return null;
    }

    public static void requireAssignable(Environment.Type target, Environment.Type type) {
        if (types.containsKey(target.getName())) {
            if (!(types.get(target.getName()).contains(type.getName()))) throw new RuntimeException("Invalid Types!");
        }
        else {
            if (target.getName() != type.getName()) throw new RuntimeException("Invalid Types!");
        }
    }

}
