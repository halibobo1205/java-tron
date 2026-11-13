package errorprone;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.matchers.Matchers;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.util.TreeScanner;
import com.sun.tools.javac.code.Symbol;
import java.util.ArrayList;
import java.util.List;

/**
 * Flags {@link java.util.Comparator}/{@link java.lang.Comparable} implementations that can never
 * return {@code 0} (e.g. {@code a <= b ? 1 : -1}).
 *
 * <p>Such a comparator violates the general contract: when two elements compare "equal" it still
 * returns a non-zero value, breaking antisymmetry. At runtime {@code List.sort} (TimSort) throws
 * {@code IllegalArgumentException: Comparison method violates its general contract!} once the list
 * is large enough and contains equal keys; otherwise it silently produces an undefined order.
 *
 * <p>The built-in ErrorProne {@code ComparisonContractViolated} checker only inspects
 * {@code compare}/{@code compareTo} <em>method declarations</em>, so it cannot see lambda
 * comparators such as {@code list.sort((a, b) -> a.t() <= b.t() ? 1 : -1)}. This checker covers
 * both the lambda form and the method-declaration form.
 *
 * <p>This is a deliberately conservative <em>syntactic</em> check: it only flags a body whose every
 * return is a non-zero {@code int} constant (a literal, or a ternary of such constants). It does no
 * data-flow analysis, so a value laundered through a variable
 * ({@code int r = c ? -1 : 1; return r;}) is not flagged.
 *
 * <p>Fix by returning {@code 0} on equality, e.g. {@code Long.compare(a, b)} or
 * {@code Comparator.comparingLong(X::t)} (append {@code .reversed()} for descending order).
 */
@AutoService(BugChecker.class)
@BugPattern(
    name = "ComparatorNeverReturnsZero",
    summary = "Comparator/compareTo can never return 0 (e.g. `? 1 : -1`), violating the comparison "
        + "contract; List.sort may throw IllegalArgumentException. Return 0 on equality, e.g. "
        + "Long.compare(a, b) or Comparator.comparingLong(...).",
    severity = BugPattern.SeverityLevel.ERROR)
public class ComparatorNeverReturnsZero extends BugChecker
    implements BugChecker.LambdaExpressionTreeMatcher, BugChecker.MethodTreeMatcher {

  private static final Matcher<ExpressionTree> IS_COMPARATOR =
      Matchers.isSubtypeOf("java.util.Comparator");

  @Override
  public Description matchLambdaExpression(LambdaExpressionTree tree, VisitorState state) {
    // Only comparator lambdas: the target functional interface is java.util.Comparator.
    // (Comparable is not a functional interface, so it can never be a lambda.)
    if (!IS_COMPARATOR.matches(tree, state)) {
      return Description.NO_MATCH;
    }
    return neverReturnsZero(tree.getBody()) ? describeMatch(tree) : Description.NO_MATCH;
  }

  @Override
  public Description matchMethod(MethodTree tree, VisitorState state) {
    if (tree.getBody() == null || !isComparatorMethod(tree, state)) {
      return Description.NO_MATCH;
    }
    return neverReturnsZero(tree.getBody()) ? describeMatch(tree) : Description.NO_MATCH;
  }

  /**
   * True if the comparator body ({@code (a,b) -> expr}, {@code (a,b) -> {..}} or a method block)
   * provably yields a non-zero {@code int} constant on every path. Conservative: any path whose
   * value cannot be proven non-zero (a method call, a subtraction, a plain {@code 0}, ...) makes
   * this return {@code false}, so legitimate comparators such as {@code Long.compare(a, b)} are
   * never flagged.
   */
  private static boolean neverReturnsZero(Tree body) {
    if (body instanceof ExpressionTree) {
      return alwaysNonZero((ExpressionTree) body);
    }
    if (body instanceof BlockTree) {
      List<ReturnTree> returns = new ArrayList<>();
      new ReturnCollector().scan(body, returns);
      if (returns.isEmpty()) {
        return false;
      }
      for (ReturnTree r : returns) {
        if (r.getExpression() == null || !alwaysNonZero(r.getExpression())) {
          return false;
        }
      }
      return true;
    }
    return false;
  }

  /** True if {@code e} is provably a non-zero {@code int} constant on every branch. */
  private static boolean alwaysNonZero(ExpressionTree e) {
    e = stripParens(e);
    Integer c = ASTHelpers.constValue(e, Integer.class);
    if (c != null) {
      return c != 0;
    }
    // Fall back for `-1` / `+1` in case constant folding did not run.
    if (e instanceof UnaryTree
        && (e.getKind() == Tree.Kind.UNARY_MINUS || e.getKind() == Tree.Kind.UNARY_PLUS)) {
      Integer operand =
          ASTHelpers.constValue(stripParens(((UnaryTree) e).getExpression()), Integer.class);
      return operand != null && operand != 0;
    }
    if (e instanceof ConditionalExpressionTree) {
      ConditionalExpressionTree cond = (ConditionalExpressionTree) e;
      return alwaysNonZero(cond.getTrueExpression()) && alwaysNonZero(cond.getFalseExpression());
    }
    return false;
  }

  private static ExpressionTree stripParens(ExpressionTree e) {
    while (e instanceof ParenthesizedTree) {
      e = ((ParenthesizedTree) e).getExpression();
    }
    return e;
  }

  /**
   * True only for methods that genuinely override {@code Comparator.compare} or
   * {@code Comparable.compareTo}. Matching by name + arity alone would wrongly flag same-named
   * overloads (e.g. {@code compareTo(long)}) and static/private helpers; those override nothing, so
   * {@link ASTHelpers#findSuperMethods} returns no comparator super-method for them.
   */
  private static boolean isComparatorMethod(MethodTree tree, VisitorState state) {
    Symbol.MethodSymbol sym = ASTHelpers.getSymbol(tree);
    if (sym == null) {
      return false;
    }
    for (Symbol.MethodSymbol superMethod : ASTHelpers.findSuperMethods(sym, state.getTypes())) {
      String ownerName = superMethod.owner.getQualifiedName().toString();
      if (ownerName.equals("java.util.Comparator") || ownerName.equals("java.lang.Comparable")) {
        return true;
      }
    }
    return false;
  }

  /** Collects return statements owned by this method/lambda, not by nested functions. */
  private static final class ReturnCollector extends TreeScanner<Void, List<ReturnTree>> {
    @Override
    public Void visitReturn(ReturnTree node, List<ReturnTree> returns) {
      returns.add(node);
      return super.visitReturn(node, returns);
    }

    @Override
    public Void visitLambdaExpression(LambdaExpressionTree node, List<ReturnTree> returns) {
      return null; // a nested lambda's returns are its own
    }

    @Override
    public Void visitClass(ClassTree node, List<ReturnTree> returns) {
      return null; // a nested / anonymous class's returns are its own
    }
  }
}
