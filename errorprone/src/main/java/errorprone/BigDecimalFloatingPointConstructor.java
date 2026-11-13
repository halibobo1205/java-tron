package errorprone;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import java.util.List;

/**
 * Prevents constructing {@link java.math.BigDecimal} from binary floating-point values.
 *
 * <p>This checks the resolved constructor signature, so it also catches {@link Double} and
 * {@link Float} arguments that javac unboxes to the {@code double} constructor, constructor
 * references such as {@code BigDecimal::new} resolved against a floating-point functional
 * interface, anonymous subclasses of {@code BigDecimal}, and named subclasses delegating via
 * {@code super(double)} -- the only source-level route into the floating-point constructor.
 */
@AutoService(BugChecker.class)
@BugPattern(
    name = "BigDecimalFloatingPointConstructor",
    summary = "Do not construct BigDecimal from a floating-point value. Use a decimal String, "
        + "for example new BigDecimal(\"0.0001\").",
    severity = BugPattern.SeverityLevel.ERROR
)
public class BigDecimalFloatingPointConstructor extends BugChecker
    implements BugChecker.NewClassTreeMatcher, BugChecker.MemberReferenceTreeMatcher,
    BugChecker.MethodInvocationTreeMatcher {

  private static final String BIG_DECIMAL = "java.math.BigDecimal";

  @Override
  public Description matchNewClass(NewClassTree tree, VisitorState state) {
    Symbol symbol = ASTHelpers.getSymbol(tree);
    if (!(symbol instanceof Symbol.MethodSymbol)) {
      return Description.NO_MATCH;
    }

    // For an anonymous subclass (new BigDecimal(...) { }) the resolved symbol is the synthetic
    // constructor of the anonymous class, so match on the type being instantiated instead of the
    // constructor owner.
    Type constructed = ASTHelpers.getType(tree.getIdentifier());
    if (constructed == null
        || !constructed.tsym.getQualifiedName().contentEquals(BIG_DECIMAL)) {
      return Description.NO_MATCH;
    }

    if (!firstParameterIsDouble((Symbol.MethodSymbol) symbol, state)) {
      return Description.NO_MATCH;
    }

    return describeMatch(tree);
  }

  @Override
  public Description matchMemberReference(MemberReferenceTree tree, VisitorState state) {
    if (tree.getMode() != MemberReferenceTree.ReferenceMode.NEW) {
      return Description.NO_MATCH;
    }

    Symbol symbol = ASTHelpers.getSymbol(tree);
    if (!(symbol instanceof Symbol.MethodSymbol)) {
      return Description.NO_MATCH;
    }

    Symbol.MethodSymbol constructor = (Symbol.MethodSymbol) symbol;
    if (!constructor.owner.getQualifiedName().contentEquals(BIG_DECIMAL)) {
      return Description.NO_MATCH;
    }

    if (!firstParameterIsDouble(constructor, state)) {
      return Description.NO_MATCH;
    }

    return describeMatch(tree);
  }

  @Override
  public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
    Symbol symbol = ASTHelpers.getSymbol(tree);
    if (!(symbol instanceof Symbol.MethodSymbol)) {
      return Description.NO_MATCH;
    }

    // A MethodInvocationTree resolving to a constructor is an explicit super(...)/this(...)
    // call, so this flags named subclasses delegating to BigDecimal(double). Every named
    // subclass must pass through such a call to reach the floating-point constructor, which
    // also covers new NamedSubclass(...) and NamedSubclass::new at their declaration site.
    Symbol.MethodSymbol method = (Symbol.MethodSymbol) symbol;
    if (!method.isConstructor()
        || !method.owner.getQualifiedName().contentEquals(BIG_DECIMAL)) {
      return Description.NO_MATCH;
    }

    if (!firstParameterIsDouble(method, state)) {
      return Description.NO_MATCH;
    }

    return describeMatch(tree);
  }

  private static boolean firstParameterIsDouble(
      Symbol.MethodSymbol constructor, VisitorState state) {
    List<Symbol.VarSymbol> parameters = constructor.getParameters();
    return !parameters.isEmpty()
        && ASTHelpers.isSameType(parameters.get(0).type, state.getSymtab().doubleType, state);
  }
}
