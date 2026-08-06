package errorprone;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.NewClassTree;
import com.sun.tools.javac.code.Symbol;
import java.util.List;

/**
 * Prevents constructing {@link java.math.BigDecimal} from binary floating-point values.
 *
 * <p>This checks the resolved constructor signature, so it also catches {@link Double} and
 * {@link Float} arguments that javac unboxes to the {@code double} constructor.
 */
@AutoService(BugChecker.class)
@BugPattern(
    name = "BigDecimalFloatingPointConstructor",
    summary = "Do not construct BigDecimal from a floating-point value. Use a decimal String, "
        + "for example new BigDecimal(\"0.0001\").",
    severity = BugPattern.SeverityLevel.ERROR
)
public class BigDecimalFloatingPointConstructor extends BugChecker
    implements BugChecker.NewClassTreeMatcher {

  private static final String BIG_DECIMAL = "java.math.BigDecimal";

  @Override
  public Description matchNewClass(NewClassTree tree, VisitorState state) {
    Symbol symbol = ASTHelpers.getSymbol(tree);
    if (!(symbol instanceof Symbol.MethodSymbol)) {
      return Description.NO_MATCH;
    }

    Symbol.MethodSymbol constructor = (Symbol.MethodSymbol) symbol;
    if (!constructor.owner.getQualifiedName().contentEquals(BIG_DECIMAL)) {
      return Description.NO_MATCH;
    }

    List<Symbol.VarSymbol> parameters = constructor.getParameters();
    if (parameters.isEmpty()
        || !ASTHelpers.isSameType(parameters.get(0).type, state.getSymtab().doubleType, state)) {
      return Description.NO_MATCH;
    }

    return describeMatch(tree);
  }
}
