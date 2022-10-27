package plc.project;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
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
        boolean mainFound = false;
        Environment.PlcObject mainReturn = null;

        for (Ast.Global global : ast.getGlobals()) {
            visit(global);
        }

        for (Ast.Function function : ast.getFunctions()) {
            Environment.PlcObject returnValue = visit(function);
            if (function.getName() == "main" && function.getParameters().size() == 0) {
                mainFound = true;
                mainReturn = returnValue;
            }
        }
        return mainReturn;
    }

    @Override
    public Environment.PlcObject visit(Ast.Global ast) {
        Environment.PlcObject valueObj = visit(ast.getValue().get());

        scope.defineVariable(ast.getName(), ast.getMutable(), valueObj);
        return Environment.NIL;
    }

    @Override
    public Environment.PlcObject visit(Ast.Function ast) {
        scope = new Scope(scope);

        for (String parameter : ast.getParameters()) {
            scope.defineVariable(parameter, true, Environment.NIL);
        }

        for (Ast.Statement statement : ast.getStatements()) {
            visit(statement);
        }

        scope = scope.getParent();
        return Environment.NIL;
    }

    @Override
    public Environment.PlcObject visit(Ast.Statement.Expression ast) {
        throw new UnsupportedOperationException(); //TODO
    }

    @Override
    public Environment.PlcObject visit(Ast.Statement.Declaration ast) {
        Environment.PlcObject valueObj = visit(ast.getValue().get());

        scope.defineVariable(ast.getName(), true, valueObj);
        return Environment.NIL;
    }

    @Override
    public Environment.PlcObject visit(Ast.Statement.Assignment ast) {
        if (!(ast.getReceiver() instanceof Ast.Expression.Access)) throw new RuntimeException("Reciever is not of type Ast.Expression.Access!");

        Ast.Expression.Access receiver = (Ast.Expression.Access) ast.getReceiver();
        Environment.Variable variable = scope.lookupVariable(receiver.getName());

        Environment.PlcObject valueObj = visit(ast.getValue());

        if (!(valueObj.getValue() instanceof Ast.Expression.Literal)) throw new RuntimeException("Invalid value to set variable to!");

        Ast.Expression.Literal value = (Ast.Expression.Literal) valueObj.getValue();

        if ((Object) receiver.getOffset() == Optional.empty()) {
            variable.setValue(Environment.create(value.getLiteral()));
        }
        else {
            Ast.Expression indexExpression = receiver.getOffset().get();
            Environment.PlcObject indexObj = visit(indexExpression);

            if (!(indexObj.getValue() instanceof Ast.Expression.Literal)) throw new RuntimeException("Invalid index!");
            int index = Integer.parseInt(((Ast.Expression.Literal) indexObj.getValue()).getLiteral().toString());
            List<Object> array = (List<Object>) variable.getValue().getValue();

            array.set(index, value.getLiteral());
            variable.setValue(Environment.create(array));
        }

        return Environment.NIL;
    }

    @Override
    public Environment.PlcObject visit(Ast.Statement.If ast) {
        throw new UnsupportedOperationException(); //TODO
    }

    @Override
    public Environment.PlcObject visit(Ast.Statement.Switch ast) {
        throw new UnsupportedOperationException(); //TODO
    }

    @Override
    public Environment.PlcObject visit(Ast.Statement.Case ast) {
        throw new UnsupportedOperationException(); //TODO
    }

    @Override
    public Environment.PlcObject visit(Ast.Statement.While ast) {
        throw new UnsupportedOperationException(); //TODO (in lecture)
    }

    @Override
    public Environment.PlcObject visit(Ast.Statement.Return ast) {
        throw new UnsupportedOperationException(); //TODO
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
        throw new UnsupportedOperationException(); //TODO
    }

    @Override
    public Environment.PlcObject visit(Ast.Expression.Access ast) {
        Environment.Variable variable = scope.lookupVariable(ast.getName());
        if ((Object) ast.getOffset() == Optional.empty()) {
            return variable.getValue();
        }
        else {
            Environment.PlcObject indexObj = visit(ast.getOffset().get());

            if (!(indexObj.getValue() instanceof Ast.Expression.Literal)) throw new RuntimeException("Invalid index!");
            int index = Integer.parseInt(((Ast.Expression.Literal) indexObj.getValue()).getLiteral().toString());

            List<Object> array = (List<Object>) variable.getValue().getValue();
            return Environment.create(array.get(index));
        }
    }

    @Override
    public Environment.PlcObject visit(Ast.Expression.Function ast) {
        throw new UnsupportedOperationException(); //TODO
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
