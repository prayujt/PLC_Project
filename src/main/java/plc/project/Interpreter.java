package plc.project;

import java.util.HashMap;
import java.util.Map;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.Optional;

public class Interpreter implements Ast.Visitor<Environment.PlcObject> {

    private Scope scope = new Scope(null);

    public Interpreter(Scope parent) {
        scope = new Scope(parent);
        scope.defineFunction("print", 1, args -> {
            System.out.println(args.get(0).getValue());
            return Environment.NIL;
        });
    }

    public Scope getScope() {
        return scope;
    }

    @Override
    public Environment.PlcObject visit(Ast.Source ast) {
        for (Ast.Global global : ast.getGlobals()) {
            visit(global);
        }

        for (Ast.Function function : ast.getFunctions()) {
            visit(function);
        }

        // expression to run main function
        Ast.Expression.Function mainFunction =
            new Ast.Expression.Function("main", Arrays.asList());

        // will automatically throw exception if main function is not found
        return visit(mainFunction);
    }

    @Override
    public Environment.PlcObject visit(Ast.Global ast) {
        Environment.PlcObject valueObj = Environment.NIL;

        Optional<Ast.Expression> expression = ast.getValue();
        if ((Object) expression != Optional.empty()) valueObj = visit(expression.get());

        scope.defineVariable(ast.getName(), ast.getMutable(), valueObj);
        return Environment.NIL;
    }

    @Override
    public Environment.PlcObject visit(Ast.Function ast) {
        List<String> placeholders = ast.getParameters();
        Scope originalScope = scope;
        java.util.function.Function<List<Environment.PlcObject>, Environment.PlcObject> function = arguments -> {
            Scope tempScope = scope;
            scope = new Scope(originalScope);
            for (int i = 0; i < arguments.size(); i++) {
                Object parameterValue = arguments.get(i).getValue();
                scope.defineVariable(placeholders.get(i), true, Environment.create(parameterValue));
            }
            try {
                for (Ast.Statement statement : ast.getStatements()) {
                    visit(statement);
                }
            }
            catch (Return r) {
                return r.value;
            }
            finally {
                scope = tempScope;
            }
            return Environment.NIL;
        };
        scope.defineFunction(ast.getName(), placeholders.size(), function);

        return Environment.NIL;
    }

    @Override
    public Environment.PlcObject visit(Ast.Statement.Expression ast) {
        visit(ast.getExpression());
        return Environment.NIL;
    }

    @Override
    public Environment.PlcObject visit(Ast.Statement.Declaration ast) {
        Environment.PlcObject valueObj = Environment.NIL;

        Optional<Ast.Expression> expression = ast.getValue();
        if ((Object) expression != Optional.empty()) valueObj = visit(expression.get());

        scope.defineVariable(ast.getName(), true, valueObj);
        return Environment.NIL;
    }

    @Override
    public Environment.PlcObject visit(Ast.Statement.Assignment ast) {
        if (!(ast.getReceiver() instanceof Ast.Expression.Access)) throw new RuntimeException("Reciever is not of type Ast.Expression.Access!");

        Ast.Expression.Access receiver = (Ast.Expression.Access) ast.getReceiver();
        Environment.Variable variable = scope.lookupVariable(receiver.getName());

        Environment.PlcObject valueObj = visit(ast.getValue());

        if ((Object) receiver.getOffset() == Optional.empty()) {
            if (variable.getMutable()) variable.setValue(valueObj);
            else throw new RuntimeException("Trying to change unmutable variable!");
        }
        else {
            Ast.Expression indexExpression = receiver.getOffset().get();
            Environment.PlcObject indexObj = visit(indexExpression);

            try {
                requireType(Class.forName("BigInteger"), indexObj);
            }
            catch (ClassNotFoundException e) {}

            int index = ((BigInteger) indexObj.getValue()).intValueExact();

            List<Object> array = (List<Object>) variable.getValue().getValue();

            array.set(index, valueObj.getValue());
            if (variable.getMutable()) variable.setValue(Environment.create(array));
            else throw new RuntimeException("Trying to change unmutable variable!");
        }

        return Environment.NIL;
    }

    @Override
    public Environment.PlcObject visit(Ast.Statement.If ast) {
        scope = new Scope(scope);
        Environment.PlcObject conditionEval = visit(ast.getCondition());

        try {
            requireType(Class.forName("Boolean"), conditionEval);
        }
        catch (ClassNotFoundException e) {}
        if ((Boolean) conditionEval.getValue()) {
            List<Ast.Statement> thenStatements = ast.getThenStatements();
            for (Ast.Statement statement : thenStatements) visit(statement);
        }
        else {
            List<Ast.Statement> elseStatements = ast.getElseStatements();
            for (Ast.Statement statement : elseStatements) visit(statement);
        }
        scope = scope.getParent();
        return Environment.NIL;
    }

    @Override
    public Environment.PlcObject visit(Ast.Statement.Switch ast) {
        scope = new Scope(scope);
        Environment.PlcObject conditionEval = visit(ast.getCondition());
        List<Ast.Statement.Case> cases = ast.getCases();
        Ast.Statement.Case defaultCase = null;
        boolean foundCase = false;

        for (Ast.Statement.Case _case : cases) {
            if (((Object) _case.getValue()) == Optional.empty()) {
                defaultCase = _case;
                continue;
            }
            Object caseCondition = visit(_case.getValue().get()).getValue();
            if (caseCondition.equals(conditionEval.getValue())) {
                foundCase = true;
                visit(_case);
            }
        }

        if (!foundCase) {
            visit(defaultCase);
        }
        scope = scope.getParent();
        return Environment.NIL;
    }

    @Override
    public Environment.PlcObject visit(Ast.Statement.Case ast) {
        scope = new Scope(scope);
        for (Ast.Statement statement : ast.getStatements()) {
            visit(statement);
        }
        scope = scope.getParent();
        return Environment.NIL;
    }

    @Override
    public Environment.PlcObject visit(Ast.Statement.While ast) {
        scope = new Scope(scope);

        Environment.PlcObject condition = visit(ast.getCondition());
        try {
            requireType(Class.forName("Boolean"), condition);
        }
        catch (ClassNotFoundException e) {}

        List<Ast.Statement> statements = ast.getStatements();
        while ((Boolean) visit(ast.getCondition()).getValue()) {
            for (Ast.Statement statement : statements) {
                visit(statement);
            }
        }

        scope = scope.getParent();
        return Environment.NIL;
    }

    @Override
    public Environment.PlcObject visit(Ast.Statement.Return ast) {
        throw new Return(visit(ast.getValue()));
    }

    @Override
    public Environment.PlcObject visit(Ast.Expression.Literal ast) {
        if (ast.getLiteral() == null) return Environment.NIL;
        return Environment.create(ast.getLiteral());
    }

    @Override
    public Environment.PlcObject visit(Ast.Expression.Group ast) {
        return visit(ast.getExpression());
    }

    @Override
    public Environment.PlcObject visit(Ast.Expression.Binary ast) {
        Environment.PlcObject left = visit(ast.getLeft());
        Environment.PlcObject right = Environment.NIL;
        String operator = ast.getOperator();

        Environment.PlcObject returnVal = Environment.NIL;

        switch (operator) {
            case "&&":
                right = visit(ast.getRight());
                try {
                    requireType(Class.forName("Boolean"), left);
                    requireType(Class.forName("Boolean"), right);
                }
                catch (ClassNotFoundException e) {}

                returnVal = Environment.create(((Boolean) left.getValue()).booleanValue() && ((Boolean) right.getValue()).booleanValue());
                break;
            case "||":
                try {
                    requireType(Class.forName("Boolean"), left);
                }
                catch (ClassNotFoundException e) {}
                boolean bool = ((Boolean) left.getValue()).booleanValue();
                if (!bool) {
                    try {
                        requireType(Class.forName("Boolean"), right);
                    }
                    catch (ClassNotFoundException e) {}
                    right = visit(ast.getRight());
                    returnVal = Environment.create(bool || ((Boolean) right.getValue()).booleanValue());
                }
                else returnVal = Environment.create(true);
                break;
            case "<":
                right = visit(ast.getRight());
                try {
                    requireType(Class.forName("Comparable"), left);
                    requireType(Class.forName("Comparable"), right);
                }
                catch (ClassNotFoundException e) {}

                Comparable<Object> leftObj = (Comparable<Object>) left.getValue();
                Comparable<Object> rightObj = (Comparable<Object>) right.getValue();
                returnVal = Environment.create(leftObj.compareTo(rightObj) < 0);
                break;
            case ">":
                right = visit(ast.getRight());
                try {
                    requireType(Class.forName("Comparable"), left);
                    requireType(Class.forName("Comparable"), right);
                }
                catch (ClassNotFoundException e) {}

                leftObj = (Comparable<Object>) left.getValue();
                rightObj = (Comparable<Object>) right.getValue();
                returnVal = Environment.create(leftObj.compareTo(rightObj) > 0);
                break;

            case "==":
                right = visit(ast.getRight());
                returnVal = Environment.create(Objects.equals(left.getValue(), right.getValue()));
                break;

            case "!=":
                right = visit(ast.getRight());
                returnVal = Environment.create(!(Objects.equals(left.getValue(), right.getValue())));
                break;

            case "+":
                right = visit(ast.getRight());

                if (left.getValue() instanceof String || right.getValue() instanceof String) {
                    returnVal = Environment.create(left.getValue().toString() + right.getValue().toString());
                }
                else if (left.getValue() instanceof BigInteger && right.getValue() instanceof BigInteger) {
                    returnVal = Environment.create(((BigInteger) left.getValue()).add((BigInteger) (right.getValue())));
                }
                else if (left.getValue() instanceof BigDecimal && right.getValue() instanceof BigDecimal) {
                    returnVal = Environment.create(((BigDecimal) left.getValue()).add((BigDecimal) (right.getValue())));
                }

                else throw new RuntimeException("Incorrect types!");
                break;

            case "-":
                right = visit(ast.getRight());
                if (left.getValue() instanceof BigInteger && right.getValue() instanceof BigInteger) {
                    returnVal = Environment.create(((BigInteger) left.getValue()).subtract((BigInteger) (right.getValue())));
                }
                else if (left.getValue() instanceof BigDecimal && right.getValue() instanceof BigDecimal) {
                    returnVal = Environment.create(((BigDecimal) left.getValue()).subtract((BigDecimal) (right.getValue())));
                }
                else throw new RuntimeException("Incorrect types!");
                break;

            case "*":
                right = visit(ast.getRight());
                if (left.getValue() instanceof BigInteger && right.getValue() instanceof BigInteger) {
                    returnVal = Environment.create(((BigInteger) left.getValue()).multiply((BigInteger) (right.getValue())));
                }
                else if (left.getValue() instanceof BigDecimal && right.getValue() instanceof BigDecimal) {
                    returnVal = Environment.create(((BigDecimal) left.getValue()).multiply((BigDecimal) (right.getValue())));
                }
                else throw new RuntimeException("Incorrect types!");
                break;

            case "/":
                right = visit(ast.getRight());
                if (left.getValue() instanceof BigInteger && right.getValue() instanceof BigInteger) {
                    returnVal = Environment.create(((BigInteger) left.getValue()).divide((BigInteger) (right.getValue())));
                }
                else if (left.getValue() instanceof BigDecimal && right.getValue() instanceof BigDecimal) {
                    returnVal = Environment.create(((BigDecimal) left.getValue()).divide((BigDecimal) (right.getValue()), RoundingMode.HALF_EVEN));
                }
                else throw new RuntimeException("Incorrect types!");
                break;

            case "^":
                right = visit(ast.getRight());
                if (!(left.getValue() instanceof BigInteger && right.getValue() instanceof BigInteger)) throw new RuntimeException("Invalid types for this operator!");
                BigInteger left_num = (BigInteger) left.getValue();
                BigInteger right_num = (BigInteger) right.getValue();
                for (BigInteger i = new BigInteger("1"); i.compareTo(right_num) == -1; i = i.add(new BigInteger("1"))) {
                    left_num = left_num.multiply(left_num);
                }
                returnVal = Environment.create(left_num);
                break;

            default:
                throw new RuntimeException("Invalid operator!");
        }

        return returnVal;
    }

    @Override
    public Environment.PlcObject visit(Ast.Expression.Access ast) {
        Environment.Variable variable = scope.lookupVariable(ast.getName());
        if ((Object) ast.getOffset() == Optional.empty()) {
            return variable.getValue();
        }
        else {
            Environment.PlcObject indexObj = visit(ast.getOffset().get());

            try {
                requireType(Class.forName("BigInteger"), indexObj);
            }
            catch (ClassNotFoundException e) {}

            int index = ((BigInteger) indexObj.getValue()).intValueExact();

            List<Object> array = (List<Object>) variable.getValue().getValue();

            if (index >= array.size()) throw new RuntimeException("Index out of range!");
            return Environment.create(array.get(index));
        }
    }

    @Override
    public Environment.PlcObject visit(Ast.Expression.Function ast) {
        List<Ast.Expression> arguments = ast.getArguments();
        List<Environment.PlcObject> argumentResults = new ArrayList<Environment.PlcObject>();

        for (Ast.Expression expression : arguments) {
            argumentResults.add(visit(expression));
        }

        Environment.Function function = scope.lookupFunction(ast.getName(), arguments.size());
        return function.invoke(argumentResults);
    }

    @Override
    public Environment.PlcObject visit(Ast.Expression.PlcList ast) {
        List<Ast.Expression> array = ast.getValues();
        ArrayList<Object> newArray = new ArrayList<Object>();
        for (Ast.Expression literal : array) {
            newArray.add(((Ast.Expression.Literal) literal).getLiteral());
        }
        return Environment.create(newArray);
    }

    /**
     * Helper function to ensure an object is of the appropriate type.
     */
    private static <T> T requireType(Class<T> type, Environment.PlcObject object) {
        if (type.isInstance(object.getValue())) {
            return type.cast(object.getValue());
        } else {
            throw new RuntimeException("Expected type " + type.getName() + ", received " + object.getValue().getClass().getName() + ".");
        }
    }

    /**
     * Exception class for returning values.
     */
    private static class Return extends RuntimeException {

        private final Environment.PlcObject value;

        private Return(Environment.PlcObject value) {
            this.value = value;
        }

    }

}
