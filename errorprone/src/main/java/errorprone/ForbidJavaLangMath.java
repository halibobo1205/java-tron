package errorprone;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;

/**
 * Forbids any direct use of {@code java.lang.Math}.
 *
 * <p>{@code java.lang.Math} permits the JIT to use platform-specific intrinsics for some
 * methods (notably the transcendental and floating-point ones), so results are not
 * guaranteed to be bit-for-bit identical across CPUs / JVMs. In a consensus system that
 * non-determinism can fork the chain. All math must therefore go through
 * {@code org.tron.common.math.StrictMathWrapper}, which is backed by {@code java.lang.StrictMath}
 * and produces reproducible results.
 *
 * <p>This checker replaces the previous regex-based {@code .github/workflows/math-check.yml}
 * scan. It resolves symbols on the type-attributed AST, so it has no false positives from
 * strings/comments or unrelated classes named {@code Math}, and it catches every usage form:
 * <ul>
 *   <li>direct calls: {@code Math.max(a, b)}</li>
 *   <li>fully-qualified calls: {@code java.lang.Math.max(a, b)}</li>
 *   <li>statically-imported calls: {@code import static java.lang.Math.max; ... max(a, b)}</li>
 *   <li>method references: {@code Math::max}</li>
 *   <li>field access: {@code Math.PI}, {@code Math.E}</li>
 *   <li>statically-imported fields used bare: {@code import static java.lang.Math.PI; ... PI}</li>
 *   <li>class literals (reflection back door): {@code Math.class}</li>
 * </ul>
 *
 * <p>{@code java.lang.StrictMath} itself is intentionally allowed — it is the deterministic
 * primitive that {@code StrictMathWrapper} and the deprecated {@code MathWrapper} are built on.
 * Those wrappers delegate to {@code StrictMath} only, so they need no exemption; the repository
 * currently has no {@code java.lang.Math} call site at all. Should one ever be unavoidable, the
 * standard Error Prone escape hatch {@code @SuppressWarnings("ForbidJavaLangMath")} applies.
 *
 * <p>Detection is limited to source-level forms. Reaching {@code java.lang.Math} by reflection on a
 * computed name (e.g. {@code Class.forName("java.lang.Math")}) or through a third-party jar is out
 * of scope, exactly as it was for the regex scan this replaces.
 */
@AutoService(BugChecker.class)
@BugPattern(
    name = "ForbidJavaLangMath",
    summary = "Direct use of java.lang.Math is forbidden: its results are not guaranteed to be "
        + "bit-for-bit identical across platforms, which can break consensus. Use "
        + "org.tron.common.math.StrictMathWrapper instead.",
    severity = BugPattern.SeverityLevel.ERROR
)
public class ForbidJavaLangMath extends BugChecker
    implements BugChecker.MethodInvocationTreeMatcher,
        BugChecker.MemberReferenceTreeMatcher,
        BugChecker.MemberSelectTreeMatcher,
        BugChecker.IdentifierTreeMatcher {

  private static final String JAVA_LANG_MATH = "java.lang.Math";

  @Override
  public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
    return flagIfMathMember(ASTHelpers.getSymbol(tree), tree);
  }

  @Override
  public Description matchMemberReference(MemberReferenceTree tree, VisitorState state) {
    return flagIfMathMember(ASTHelpers.getSymbol(tree), tree);
  }

  @Override
  public Description matchMemberSelect(MemberSelectTree tree, VisitorState state) {
    // Type-level references such as the class literal `Math.class` resolve to the Math
    // ClassSymbol rather than a member, and are a back door to java.lang.Math via reflection
    // (e.g. Math.class.getMethod("sin").invoke(...)). Flag them explicitly.
    if (tree.getIdentifier().contentEquals("class")
        && isJavaLangMathClass(ASTHelpers.getSymbol(tree.getExpression()))) {
      return describeMatch(tree);
    }
    // Method selects (Math.max) are already reported via matchMethodInvocation /
    // matchMemberReference; only flag field selects (Math.PI, Math.E) here to avoid
    // double-reporting the same usage.
    Symbol sym = ASTHelpers.getSymbol(tree);
    if (!(sym instanceof Symbol.VarSymbol)) {
      return Description.NO_MATCH;
    }
    return flagIfMathMember(sym, tree);
  }

  @Override
  public Description matchIdentifier(IdentifierTree tree, VisitorState state) {
    // Catches statically-imported constants referenced bare (e.g. `import static
    // java.lang.Math.PI; ... double c = PI;`). Statically-imported *methods* are caught by
    // matchMethodInvocation, so restrict this to fields to avoid double-reporting.
    Symbol sym = ASTHelpers.getSymbol(tree);
    if (!(sym instanceof Symbol.VarSymbol)) {
      return Description.NO_MATCH;
    }
    return flagIfMathMember(sym, tree);
  }

  private static boolean isJavaLangMathClass(Symbol sym) {
    return sym instanceof Symbol.ClassSymbol
        && ((Symbol.ClassSymbol) sym).getQualifiedName().contentEquals(JAVA_LANG_MATH);
  }

  private Description flagIfMathMember(Symbol sym, Tree tree) {
    if (sym == null) {
      return Description.NO_MATCH;
    }
    Symbol.ClassSymbol enclosingClass = ASTHelpers.enclosingClass(sym);
    if (enclosingClass == null) {
      return Description.NO_MATCH;
    }
    if (enclosingClass.getQualifiedName().contentEquals(JAVA_LANG_MATH)) {
      return describeMatch(tree);
    }
    return Description.NO_MATCH;
  }
}
