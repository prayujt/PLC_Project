package plc.project;

import java.util.List;
import java.util.ArrayList;
import java.math.BigInteger;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * The parser takes the sequence of tokens emitted by the lexer and turns that
 * into a structured representation of the program, called the Abstract Syntax
 * Tree (AST).
 *
 * The parser has a similar architecture to the lexer, just with {@link Token}s
 * instead of characters. As before, {@link #peek(Object...)} and {@link
 * #match(Object...)} are helpers to make the implementation easier.
 *
 * This type of parser is called <em>recursive descent</em>. Each rule in our
 * grammar will have it's own function, and reference to other rules correspond
 * to calling that functions.
 */
public final class Parser {

    private final TokenStream tokens;

    public Parser(List<Token> tokens) {
        this.tokens = new TokenStream(tokens);
    }

    public int getIndex() {
        if (tokens.has(0)) return tokens.get(0).getIndex();
        else return tokens.get(-1).getIndex() + tokens.get(-1).getLiteral().length();
    }

    /**
     * Parses the {@code source} rule.
     */
    public Ast.Source parseSource() throws ParseException {
        List<Ast.Global> globals = new ArrayList<Ast.Global>();
        List<Ast.Function> functions = new ArrayList<Ast.Function>();
        while (peek("LIST") || peek("VAR") || peek("VAL")) {
            globals.add(parseGlobal());
        }
        while (peek("FUN")) {
            functions.add(parseFunction());
        }
        if (tokens.has(0)) throw new ParseException("Expressions outside of function declaration", getIndex());
        return new Ast.Source(globals, functions);
    }

    /**
     * Parses the {@code field} rule. This method should only be called if the
     * next tokens start a global, aka {@code LIST|VAL|VAR}.
     */
    public Ast.Global parseGlobal() throws ParseException {
        Ast.Global global = null;
        if (match("LIST")) global = parseList();
        else if (match("VAR")) global = parseMutable();
        else if (match("VAL")) global = parseImmutable();

        if (!match(";")) throw new ParseException("Missing semicolon", getIndex());
        return global;
    }

    /**
     * Parses the {@code list} rule. This method should only be called if the
     * next token declares a list, aka {@code LIST}.
     */
    public Ast.Global parseList() throws ParseException {
        List<Ast.Expression> expressions = new ArrayList<Ast.Expression>();
        String name;

        if (!peek(Token.Type.IDENTIFIER)) throw new ParseException("Missing identifier", getIndex());
        else name = tokens.get(0).getLiteral();
        tokens.advance();

        if (!match("=")) throw new ParseException("Missing equal", getIndex());
        if (!match("[")) throw new ParseException("Missing opening bracket", getIndex());

        while (!match("]")) {
            if (peek(",")) throw new ParseException("Leading Comma", getIndex());
            Ast.Expression expression = parseExpression();
            expressions.add(expression);
            if (!peek("]") && !peek(",")) throw new ParseException("Missing Comma", getIndex());
            if (match(",") && match("]")) throw new ParseException("Trailing Comma", getIndex());
        }

        return new Ast.Global(name, true, Optional.of(new Ast.Expression.PlcList(expressions)));
    }

    /**
     * Parses the {@code mutable} rule. This method should only be called if the
     * next token declares a mutable global variable, aka {@code VAR}.
     */
    public Ast.Global parseMutable() throws ParseException {
        String name;
        if (!peek(Token.Type.IDENTIFIER)) throw new ParseException("Missing identifier", getIndex());
        else name = tokens.get(0).getLiteral();
        tokens.advance();

        if (match("=")) return new Ast.Global(name, true, Optional.of(parseExpression()));
        else return new Ast.Global(name, true, Optional.empty());
    }

    /**
     * Parses the {@code immutable} rule. This method should only be called if the
     * next token declares an immutable global variable, aka {@code VAL}.
     */
    public Ast.Global parseImmutable() throws ParseException {
        String name;
        if (!peek(Token.Type.IDENTIFIER)) throw new ParseException("Missing identifier", getIndex());
        else name = tokens.get(0).getLiteral();
        tokens.advance();

        if (!match("=")) throw new ParseException("Missing equal", getIndex());
        return new Ast.Global(name, false, Optional.of(parseExpression()));
    }

    /**
     * Parses the {@code function} rule. This method should only be called if the
     * next tokens start a method, aka {@code FUN}.
     */
    public Ast.Function parseFunction() throws ParseException {
        match("FUN");

        if (!peek(Token.Type.IDENTIFIER)) throw new ParseException("Not a valid function opening!", getIndex());
        String functionName = tokens.get(0).getLiteral();
        tokens.advance();

        if (!match("(")) throw new ParseException("Missing opening parenthesis", getIndex());

        List<String> parameters = new ArrayList<String>();

        while (!match(")")) {
            if (peek(",")) throw new ParseException("Leading Comma", getIndex());
            if (!peek(Token.Type.IDENTIFIER)) throw new ParseException("Not a valid parameter!", getIndex());
            String parameter = tokens.get(0).getLiteral();
            parameters.add(parameter);
            tokens.advance();
            if (!peek(")") && !peek(",")) throw new ParseException("Missing Comma", getIndex());
            if (match(",") && peek(")")) throw new ParseException("Trailing Comma", getIndex());
        }

        if (!match("DO")) throw new ParseException("Missing DO!", getIndex());
        List<Ast.Statement> statements = parseBlock();

        if (!match("END")) throw new ParseException("Missing END!", getIndex());
        return new Ast.Function(functionName, parameters, statements);
    }

    /**
     * Parses the {@code block} rule. This method should only be called if the
     * preceding token indicates the opening a block.
     */
    public List<Ast.Statement> parseBlock() throws ParseException {
        List<Ast.Statement> statements = new ArrayList<Ast.Statement>();
        while (!peek("END") && !peek("CASE") && !peek("ELSE") && !peek("DEFAULT")) { // TODO Find another way to end block
            statements.add(parseStatement());
        }
        return statements;
    }

    /**
     * Parses the {@code statement} rule and delegates to the necessary method.
     * If the next tokens do not start a declaration, if, while, or return
     * statement, then it is an expression/assignment statement.
     */
    public Ast.Statement parseStatement() throws ParseException {
        if (match("LET")) return parseDeclarationStatement();
        if (match("SWITCH")) return parseSwitchStatement();
        if (match("IF")) return parseIfStatement();
        if (match("WHILE")) return parseWhileStatement();
        if (match("RETURN")) return parseReturnStatement();
        else {
            Ast.Expression expression = parseExpression();
            if (match("=")) {
                Ast.Expression expression2 = parseExpression();
                if (!(expression instanceof Ast.Expression.Access)) throw new ParseException("Invalid left side of assignment!", getIndex());
                if (!match(";")) throw new ParseException("Missing semicolon", getIndex());
                return new Ast.Statement.Assignment(expression, expression2);
            }
            if (!match(";")) throw new ParseException("Missing semicolon", getIndex());
            return new Ast.Statement.Expression(expression);
        }
    }

    /**
     * Parses a declaration statement from the {@code statement} rule. This
     * method should only be called if the next tokens start a declaration
     * statement, aka {@code LET}.
     */
    public Ast.Statement.Declaration parseDeclarationStatement() throws ParseException {
        if (!peek(Token.Type.IDENTIFIER)) throw new ParseException("Missing variable name!", getIndex());
        Optional<Ast.Expression> expression = Optional.empty();

        String name = tokens.get(0).getLiteral();
        tokens.advance();

        if (match("=")) expression = Optional.of(parseExpression());
        if (!match(";")) throw new ParseException("Missing semicolon", getIndex());

        return new Ast.Statement.Declaration(name, expression);
    }

    /**
     * Parses an if statement from the {@code statement} rule. This method
     * should only be called if the next tokens start an if statement, aka
     * {@code IF}.
     */
    public Ast.Statement.If parseIfStatement() throws ParseException {
        Ast.Expression condition = parseExpression();
        List<Ast.Statement> elseStatements = new ArrayList<Ast.Statement>();

        if (!match("DO")) throw new ParseException("Missing DO!", getIndex());
        List<Ast.Statement> ifStatements = parseBlock();

        if (match("ELSE")) elseStatements = parseBlock();

        if (!match("END")) throw new ParseException("Missing END!", getIndex());

        return new Ast.Statement.If(condition, ifStatements, elseStatements);
    }

    /**
     * Parses a switch statement from the {@code statement} rule. This method
     * should only be called if the next tokens start a switch statement, aka
     * {@code SWITCH}.
     */
    public Ast.Statement.Switch parseSwitchStatement() throws ParseException {
        Ast.Expression expression = parseExpression();
        List<Ast.Statement.Case> cases = new ArrayList<Ast.Statement.Case>();
        List<Ast.Statement> statements;

        while (match("CASE")) cases.add(parseCaseStatement());

        if (!match("DEFAULT")) throw new ParseException("Missing default case", getIndex());
        statements = parseBlock();
        cases.add(new Ast.Statement.Case(Optional.empty(), statements));

        return new Ast.Statement.Switch(expression, cases);
    }

    /**
     * Parses a case or default statement block from the {@code switch} rule. 
     * This method should only be called if the next tokens start the case or 
     * default block of a switch statement, aka {@code CASE} or {@code DEFAULT}.
     */
    public Ast.Statement.Case parseCaseStatement() throws ParseException {
        Ast.Expression condition = parseExpression();
        if (!match(":")) throw new ParseException("Missing colon", getIndex());
        List<Ast.Statement> statements = parseBlock();
        return new Ast.Statement.Case(Optional.of(condition), statements);
    }

    /**
     * Parses a while statement from the {@code statement} rule. This method
     * should only be called if the next tokens start a while statement, aka
     * {@code WHILE}.
     */
    public Ast.Statement.While parseWhileStatement() throws ParseException {
        Ast.Expression condition = parseExpression();

        if (!match("DO")) throw new ParseException("Missing DO!", getIndex());
        List<Ast.Statement> statements = parseBlock();
        if (!match("END")) throw new ParseException("Missing END!", getIndex());

        return new Ast.Statement.While(condition, statements);
    }

    /**
     * Parses a return statement from the {@code statement} rule. This method
     * should only be called if the next tokens start a return statement, aka
     * {@code RETURN}.
     */
    public Ast.Statement.Return parseReturnStatement() throws ParseException {
        Ast.Expression expression = parseExpression();
        if (!match(";")) throw new ParseException("Missing semicolon", getIndex());
        return new Ast.Statement.Return(expression);
    }

    /**
     * Parses the {@code expression} rule.
     */
    public Ast.Expression parseExpression() throws ParseException {
        return parseLogicalExpression();
    }

    /**
     * Parses the {@code logical-expression} rule.
     */
    public Ast.Expression parseLogicalExpression() throws ParseException {
        Ast.Expression comparasionExpressionLeft = parseComparisonExpression();
        if (peek("&&") || peek("||")) {
            String operator = tokens.get(0).getLiteral();
            tokens.advance();
            Ast.Expression comparasionExpressionRight = parseComparisonExpression();
            Ast.Expression.Binary result =
                new Ast.Expression.Binary(operator, comparasionExpressionLeft, comparasionExpressionRight);
            while (peek("&&") || peek("||")) {
                operator = tokens.get(0).getLiteral();
                tokens.advance();
                result = new Ast.Expression.Binary(operator, result, parseComparisonExpression());
            }
            return result;
        }
        return comparasionExpressionLeft;
    }

    /**
     * Parses the {@code equality-expression} rule.
     */
    public Ast.Expression parseComparisonExpression() throws ParseException {
        Ast.Expression additiveExpressionLeft = parseAdditiveExpression();
        if (peek("<") || peek(">") || peek("==") || peek("!=")) {
            String operator = tokens.get(0).getLiteral();
            tokens.advance();
            Ast.Expression additiveExpressionRight = parseAdditiveExpression();
            Ast.Expression.Binary result =
                new Ast.Expression.Binary(operator, additiveExpressionLeft, additiveExpressionRight);
            while (peek("<") || peek(">") || peek("==") || peek("!=")) {
                operator = tokens.get(0).getLiteral();
                tokens.advance();
                result = new Ast.Expression.Binary(operator, result, parseAdditiveExpression());
            }
            return result;
        }
        return additiveExpressionLeft;
    }

    /**
     * Parses the {@code additive-expression} rule.
     */
    public Ast.Expression parseAdditiveExpression() throws ParseException {
        Ast.Expression multiplicativeExpressionLeft = parseMultiplicativeExpression();
        if (peek("+") || peek("-")) {
            String operator = tokens.get(0).getLiteral();
            tokens.advance();
            Ast.Expression multiplicativeExpressionRight = parseMultiplicativeExpression();
            Ast.Expression.Binary result = new Ast.Expression.Binary(operator, multiplicativeExpressionLeft, multiplicativeExpressionRight);
            while (peek("+") || peek("-")) {
                operator = tokens.get(0).getLiteral();
                tokens.advance();
                result = new Ast.Expression.Binary(operator, result, parseMultiplicativeExpression());
            }
            return result;
        }
        return multiplicativeExpressionLeft;
    }

    /**
     * Parses the {@code multiplicative-expression} rule.
     */
    public Ast.Expression parseMultiplicativeExpression() throws ParseException {
        Ast.Expression primaryExpressionLeft = parsePrimaryExpression();
        if (peek("*") || peek("/") || peek("^")) {
            String operator = tokens.get(0).getLiteral();
            tokens.advance();
            Ast.Expression primaryExpressionRight = parsePrimaryExpression();
            Ast.Expression.Binary result = new Ast.Expression.Binary(operator, primaryExpressionLeft, primaryExpressionRight);
            while (peek("*") || peek("/") || peek("^")) {
                operator = tokens.get(0).getLiteral();
                tokens.advance();
                result = new Ast.Expression.Binary(operator, result, parsePrimaryExpression());
            }
            return result;
        }
        return primaryExpressionLeft;
    }

    /**
     * Parses the {@code primary-expression} rule. This is the top-level rule
     * for expressions and includes literal values, grouping, variables, and
     * functions. It may be helpful to break these up into other methods but is
     * not strictly necessary.
     */
    public Ast.Expression parsePrimaryExpression() throws ParseException {
        if (!tokens.has(0)) throw new ParseException("Missing expression", getIndex());
        String literal = tokens.get(0).getLiteral();
        if (match(Token.Type.INTEGER)) return new Ast.Expression.Literal(new BigInteger(literal));
        else if (match(Token.Type.DECIMAL)) return new Ast.Expression.Literal(new BigDecimal(literal));
        else if (match(Token.Type.CHARACTER)) {
            String character = literal.substring(1, literal.length() - 1);
            char[] characters = character.toCharArray();
            if (characters.length == 2) {
                Character newChar;
                switch (characters[1]) {
                    case 'b':
                        newChar = '\b';
                        break;
                    case 'n':
                        newChar = '\n';
                        break;
                    case 'r':
                        newChar = '\r';
                        break;
                    case 't':
                        newChar = '\t';
                        break;
                    default:
                        newChar = characters[1];
                        break;
                }
                return new Ast.Expression.Literal(new Character(newChar));
            }
            if (characters[0] == '\\') throw new ParseException("Invalid character!", getIndex());
            return new Ast.Expression.Literal(new Character(characters[0]));
        }
        else if (match(Token.Type.STRING)) {
            String str = literal.substring(1, literal.length() - 1)
                .replace("\\b", "\b")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\'", "\'")
                .replace("\\\\", "\\");
            return new Ast.Expression.Literal(str);
        }
        else if (match(Token.Type.IDENTIFIER)) {
            if (literal == "TRUE") return new Ast.Expression.Literal(new Boolean(true));
            if (literal == "FALSE") return new Ast.Expression.Literal(new Boolean(false));
            else if (literal == "NIL") return new Ast.Expression.Literal(null);
            if (match("(")) {
                List<Ast.Expression> arguments = new ArrayList<Ast.Expression>();
                while (!match(")")) {
                    if (peek(",")) throw new ParseException("Leading Comma", getIndex());
                    Ast.Expression expression = parseExpression();
                    arguments.add(expression);
                    if (!peek(")") && !peek(",")) throw new ParseException("Missing Comma", getIndex());
                    if (match(",") && peek(")")) throw new ParseException("Trailing Comma", getIndex());
                }
                return new Ast.Expression.Function(literal, arguments);
            }
            if (match("[")) {
                Ast.Expression expression = parseExpression();
                if (!match("]")) throw new ParseException("Missing end square bracket", getIndex());
                return new Ast.Expression.Access(Optional.of(expression), literal);
            }
            return new Ast.Expression.Access(Optional.empty(), literal);
        }
        else if (match("(")) {
            Ast.Expression expression = parseExpression();
            if (!match(")")) throw new ParseException("Missing end parenthesis", getIndex());
            return new Ast.Expression.Group(expression);
        }

        throw new ParseException("Not a valid expression", getIndex());
    }

    /**
     * As in the lexer, returns {@code true} if the current sequence of tokens
     * matches the given patterns. Unlike the lexer, the pattern is not a regex;
     * instead it is either a {@link Token.Type}, which matches if the token's
     * type is the same, or a {@link String}, which matches if the token's
     * literal is the same.
     *
     * In other words, {@code Token(IDENTIFIER, "literal")} is matched by both
     * {@code peek(Token.Type.IDENTIFIER)} and {@code peek("literal")}.
     */
    private boolean peek(Object... patterns) {
        for (int i = 0; i < patterns.length; i++) {
            if (!tokens.has(i)) return false;
            else if (patterns[i] instanceof Token.Type) {
                if (patterns[i] != tokens.get(i).getType()) return false;
            }
            else if (patterns[i] instanceof String) {
                if (!patterns[i].equals(tokens.get(i).getLiteral())) return false;
            }
            else throw new AssertionError("Invalid pattern object: " + patterns[i].getClass());
        }
        return true;
    }

    /**
     * As in the lexer, returns {@code true} if {@link #peek(Object...)} is true
     * and advances the token stream.
     */
    private boolean match(Object... patterns) {
        boolean peek = peek(patterns);
        if (peek) {
            for (int i = 0; i < patterns.length; i++) tokens.advance();
        }
        return peek;
    }

    private static final class TokenStream {

        private final List<Token> tokens;
        private int index = 0;

        private TokenStream(List<Token> tokens) {
            this.tokens = tokens;
        }

        /**
         * Returns true if there is a token at index + offset.
         */
        public boolean has(int offset) {
            return index + offset < tokens.size();
        }

        /**
         * Gets the token at index + offset.
         */
        public Token get(int offset) {
            return tokens.get(index + offset);
        }

        /**
         * Advances to the next token, incrementing the index.
         */
        public void advance() {
            index++;
        }

    }

}
