package errorprone;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeTag;
import java.util.Objects;

@AutoService(BugChecker.class)
@BugPattern(
    name = "UnsafeArithmeticChecker",
    summary = "Use BigDecimal for double/float or StrictMathWrapper.*Exact methods. "
        + "If safe by protocol constraint,"
        + " suppress with @SuppressWarnings(\"UnsafeArithmeticChecker\")",
    severity = BugPattern.SeverityLevel.WARNING
)
public class UnsafeArithmeticChecker extends BugChecker
    implements BugChecker.BinaryTreeMatcher,
    BugChecker.CompoundAssignmentTreeMatcher,
    BugChecker.UnaryTreeMatcher,
    BugChecker.TypeCastTreeMatcher,
    BugChecker.MethodInvocationTreeMatcher {

  private static final String BIG_INTEGER = "java.math.BigInteger";
  private static final String BIG_DECIMAL = "java.math.BigDecimal";

  // Binary operators: + - * /
  @Override
  public Description matchBinary(BinaryTree tree, VisitorState state) {
    switch (tree.getKind()) {
      case PLUS:
      case MINUS:
      case MULTIPLY:
      case DIVIDE:
        break;
      default:
        return Description.NO_MATCH;
    }
    Type type = ASTHelpers.getType(tree);
    if (!isPrimitiveNumeric(type)) {
      return Description.NO_MATCH;
    }
    if (ASTHelpers.constValue(tree) != null) {
      return Description.NO_MATCH;
    }
    return describeMatch(tree);
  }

  // Compound assignments: += -= *= /=
  @Override
  public Description matchCompoundAssignment(CompoundAssignmentTree tree, VisitorState state) {
    switch (tree.getKind()) {
      case PLUS_ASSIGNMENT:
      case MINUS_ASSIGNMENT:
      case MULTIPLY_ASSIGNMENT:
      case DIVIDE_ASSIGNMENT:
        break;
      default:
        return Description.NO_MATCH;
    }
    Type leftType = ASTHelpers.getType(tree.getVariable());
    return isPrimitiveNumeric(leftType) ? describeMatch(tree) : Description.NO_MATCH;
  }

  // Unary operators: ++ --
  @Override
  public Description matchUnary(UnaryTree tree, VisitorState state) {
    switch (tree.getKind()) {
      case PREFIX_INCREMENT:
      case PREFIX_DECREMENT:
      case POSTFIX_INCREMENT:
      case POSTFIX_DECREMENT:
        break;
      default:
        return Description.NO_MATCH;
    }
    TreePath path = state.getPath();
    TreePath parentPath = path.getParentPath();
    TreePath grandParentPath = parentPath != null ? parentPath.getParentPath() : null;
    Tree grandParent = grandParentPath != null ? grandParentPath.getLeaf() : null;

    if (grandParent instanceof com.sun.source.tree.ForLoopTree) {
      ForLoopTree forLoop = (ForLoopTree) grandParent;
      boolean inUpdate = forLoop.getUpdate().stream()
          .anyMatch(stmt -> stmt.getExpression() == tree);
      if (inUpdate) {
        return Description.NO_MATCH;
      }
    }
    Type type = ASTHelpers.getType(tree.getExpression());
    return isPrimitiveNumeric(type) ? describeMatch(tree) : Description.NO_MATCH;
  }

  // Narrowing type casts: e.g. (int) longVal, (long) doubleVal
  @Override
  public Description matchTypeCast(TypeCastTree tree, VisitorState state) {
    Type castType = ASTHelpers.getType(tree.getType());
    Type exprType = ASTHelpers.getType(tree.getExpression());
    if (castType == null || exprType == null) {
      return Description.NO_MATCH;
    }
    // Only flag narrowing casts between primitive numeric types
    if (isPrimitiveNumeric(castType) && isPrimitiveNumeric(exprType)
        && isNarrowing(exprType, castType)) {
      return describeMatch(tree);
    }
    return Description.NO_MATCH;
  }

  // Unsafe conversions: BigInteger/BigDecimal.longValue() / intValue() / doubleValue()
  @Override
  public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
    Symbol.MethodSymbol symbol = ASTHelpers.getSymbol(tree);
    if (symbol == null) {
      return Description.NO_MATCH;
    }
    String methodName = symbol.getSimpleName().toString();
    if (!methodName.equals("longValue")
        && !methodName.equals("intValue")
        && !methodName.equals("doubleValue")) {
      return Description.NO_MATCH;
    }
    // Only flag calls on BigInteger or BigDecimal — use *Exact() variants instead
    String ownerName = symbol.owner.getQualifiedName().toString();
    if (ownerName.equals(BIG_INTEGER) || ownerName.equals(BIG_DECIMAL)) {
      return describeMatch(tree);
    }
    return Description.NO_MATCH;
  }

  // Helpers

  private boolean isPrimitiveNumeric(Type type) {
    if (type == null) {
      return false;
    }
    switch (type.getTag()) {
      case INT:
      case LONG:
      case DOUBLE:
      case FLOAT:
        return true;
      default:
        break;
    }
    String name = type.tsym.getQualifiedName().toString();
    return name.equals("java.lang.Integer")
        || name.equals("java.lang.Long")
        || name.equals("java.lang.Double")
        || name.equals("java.lang.Float");
  }

  /**
   * Returns true if casting {@code from} to {@code to} risks data loss, covering:
   * <ul>
   *   <li>JLS §5.1.3 narrowing conversions (e.g. long→int, float→int, double→float)</li>
   *   <li>Precision-lossy widenings: int→float, long→float, long→double</li>
   * </ul>
   */
  private boolean isNarrowing(Type from, Type to) {
    int fromOrd = typeOrdinal(from);
    int toOrd = typeOrdinal(to);
    if (fromOrd < 0 || toOrd < 0) {
      return false;
    }
    // JLS §5.1.3: target sits lower in the widening-conversion hierarchy
    if (fromOrd > toOrd) {
      return true;
    }
    // Widening casts that still lose precision: int/long→float, long→double
    return isPrecisionLossyWidening(from, to);
  }

  /**
   * Ordinal follows the JLS §5.1.2 widening primitive conversion order:
   * byte(0) < short(1) < int(2) < long(3) < float(4) < double(5).
   */
  private int typeOrdinal(Type type) {
    if (type == null) {
      return -1;
    }
    switch (type.getTag()) {
      case BYTE:   return 0;
      case SHORT:  return 1;
      case INT:    return 2;
      case LONG:   return 3;
      case FLOAT:  return 4;
      case DOUBLE: return 5;
      default:     return -1;
    }
  }

  /**
   * Returns true for widening casts that still lose significant bits:
   * int→float (32-bit integer into 24-bit mantissa),
   * long→float, long→double (64-bit integer into 24- or 53-bit mantissa).
   */
  private boolean isPrecisionLossyWidening(Type from, Type to) {
    switch (to.getTag()) {
      case FLOAT:
        switch (from.getTag()) {
          case INT:
          case LONG:
            return true;
          default:
            return false;
        }
      case DOUBLE:
        return from.getTag() == TypeTag.LONG;
      default:
        return false;
    }
  }
}
