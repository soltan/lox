package lox.java;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Random;

import static lox.java.Lox.error;
import static lox.java.LoxType.*;

class Annotate extends Pass<List<Stmt>, List<Stmt>>
  implements Stmt.Visitor<Void>, Expr.Visitor<Void> {
  private static final Random rand = new Random();
  private final Map<String, Expr.Symbol> types = new HashMap<>();

  private Scope<String> scope = new Scope<>();
  private Stmt.Function currentFunction = null;
  private boolean returnFound = false;

  // boilerplate start
  Annotate(List<Stmt> input) {
    super(input);
  }

  public List<Stmt> runPass() {
    for (Stmt stmt : input) {
      stmt.accept(this);
    }
    return input;
  }

  public Void visitStmt(Stmt.Expression stmt) { return stmt.expression.accept(this); }
  public Void visitStmt(Stmt.Print stmt) { return stmt.expression.accept(this); }
  public Void visitStmt(Stmt.If stmt) {
    stmt.condition.accept(this);
    stmt.then.accept(this);
    return stmt.otherwise == null ? null : stmt.otherwise.accept(this);
  }

  public Void visitStmt(Stmt.While stmt) {
    stmt.condition.accept(this);
    return stmt.body.accept(this);
  }

  public Void visitStmt(Stmt.LoopControl keyword) { return null; }

  // boilerplate end

  public Void visitStmt(Stmt.Var var) {
    create(var.identifier);
    return null;
  }

  public Void visitStmt(Stmt.Block block) {
    Scope<String> oldScope = scope;
    scope = new Scope<>(scope);
    for (Stmt stmt : block.statements) {
      stmt.accept(this);
    }
    scope = oldScope;
    return null;
  }

  public Void visitStmt(Stmt.Function func) {
    func.identifier.arity = func.arguments.size();
    String oldName = func.identifier.token.lexeme;
    create(func.identifier);

    Stmt.Function oldFunc = currentFunction;
    boolean oldReturn = returnFound;

    // push these on the stack
    currentFunction = func;
    returnFound = false;

    func.body.accept(this);
    if (!returnFound) {
      // automatically return void if user didn't put a return statement
      if (func.identifier.type == VOID) {
        func.body.statements.add(new Stmt.Return(null, new Token(
          Token.Type.RETURN, "return", -1, -1, null)));
      } else {
        error(func.identifier.token.line, func.identifier.token.column,
          String.format("Must return a value for function '%s' declared as " + func.identifier.type,
            oldName));
      }
    }

    // pop them off
    currentFunction = oldFunc;
    returnFound = oldReturn;

    return null;
  }

  @Override
  public Void visitStmt(Stmt.Return stmt) {
    if (currentFunction == null) {
      error(stmt.token.line, stmt.token.column, "Cannot return unless inside function");
      return null;
    } else if (returnFound) {
      // TODO: be smart and allow if (x) return true else return false;
      error(stmt.token.line, stmt.token.column, "Cannot return more than once inside a function");
      return null;
    }
    returnFound = true;
    LoxType actual;
    if (stmt.value != null) {
      stmt.value.accept(this);
      actual = stmt.value.type;
    } else {
      actual = VOID;
    }
    if (actual != currentFunction.identifier.type) {
      error(stmt.token.line, stmt.token.column, "Illegal return type: function declared with type "
        + currentFunction.identifier.type + ", got " + actual);
    }
    return null;
  }

  public Void visitExpr(Expr.Unary expr) { return expr.right.accept(this); }
  public Void visitExpr(Expr.Binary expr) {
    expr.left.accept(this);
    return expr.right.accept(this);
  }

  public Void visitExpr(Expr.Logical expr) {
    expr.left.accept(this);
    return expr.right.accept(this);
  }
  public Void visitExpr(Expr.Grouping expr) { return expr.expression.accept(this); }
  public Void visitExpr(Expr.Literal expr) { return null; }

  public Void visitExpr(Expr.Symbol symbol) {
    String oldName = symbol.token.lexeme;
    /* ideally we would replace 'symbol' outright,
     * but we don't have a proper reference */
    symbol.token.lexeme = scope.get(oldName);
    Expr.Symbol shouldBe = types.get(symbol.token.lexeme);
    if (shouldBe == null) {
      error(symbol.token.line, symbol.token.column,
          "Undeclared variable " + oldName);
    } else {
      symbol.type = shouldBe.type;
      symbol.arity = shouldBe.arity;
    }
    return null;
  }

  public Void visitExpr(Expr.Assign expr) {
    Expr.Symbol old = expr.lvalue;
    expr.lvalue = retrieve(old);
    expr.rvalue.accept(this);
    if (expr.lvalue == null) error(old.token.line, old.token.column,
        "Undeclared variable " + old.token.lexeme);
    else {
      try {
        expr.type = assertPromotable(expr.lvalue.type, expr.rvalue.type, null);
      } catch (TypeError e) {
        error(expr.token.line, expr.token.column,
        "Cannot assign expression of type " + expr.rvalue.type + " to variable of type " + expr.lvalue.type);
      }
    }
    return null;
  }

  public Void visitExpr(Expr.Call call) {
    String oldName = call.callee.token.lexeme;
    call.callee = retrieve(call.callee);

    if (call.callee == null) {
      error(call.token.line, call.token.column, "Undeclared function " + oldName);
    } else if (call.arguments.size() != call.callee.arity) {
      error(call.token.line, call.token.column,
          "Invalid number of arguments to function '" + oldName
          + "' (expected " + call.callee.arity
          + ", got " + call.arguments.size()
          + ')');
    } else {
      call.type = call.callee.type;
    }

    return null;
  }

  private void create(Expr.Symbol symbol) {
    if (scope.getImmediate(symbol.token.lexeme) != null) {
      error(symbol.token.line, symbol.token.column,
          "Illegal redeclaration of variable " + symbol.token.lexeme);
      return;
    }
    String mangled = mangle(symbol.token.lexeme);
    scope.put(symbol.token.lexeme, mangled);
    symbol.token.lexeme = mangled;

    // TODO
    assert symbol.type != null;
    types.put(mangled, symbol);
  }

  private Expr.Symbol retrieve(Expr.Symbol symbol) {
    return types.get(scope.get(symbol.token.lexeme));
  }

  private String mangle(String name) {
    name += '_';
    while (types.containsKey(name)) {
      name += genChar();
    }
    return name;
  }

  private char genChar() {
    return (char)(rand.nextInt(26) + 'a');
  }
}
