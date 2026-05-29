package errorprone;

import com.google.errorprone.CompilationTestHelper;
import org.junit.Test;

/**
 * Tests for {@link ForbidJavaLangMath}.
 *
 * <p>The repository currently contains zero {@code java.lang.Math} call sites, so a matcher that
 * silently stops firing would produce no CI signal at all. These tests are the only thing that
 * keeps the checker honest.
 */
public class ForbidJavaLangMathTest {

  private final CompilationTestHelper helper =
      CompilationTestHelper.newInstance(ForbidJavaLangMath.class, getClass());

  // ---------- positive: must be flagged ----------

  @Test
  public void directCall_flagged() {
    helper
        .addSourceLines(
            "Test.java",
            "class Test {",
            "  long f(long a, long b) {",
            "    // BUG: Diagnostic contains: ForbidJavaLangMath",
            "    return Math.max(a, b);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void fullyQualifiedCall_flagged() {
    helper
        .addSourceLines(
            "Test.java",
            "class Test {",
            "  long f(long a, long b) {",
            "    // BUG: Diagnostic contains: ForbidJavaLangMath",
            "    return java.lang.Math.max(a, b);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void staticallyImportedMethod_flagged() {
    helper
        .addSourceLines(
            "Test.java",
            "import static java.lang.Math.max;",
            "class Test {",
            "  long f(long a, long b) {",
            "    // BUG: Diagnostic contains: ForbidJavaLangMath",
            "    return max(a, b);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void staticallyImportedFieldUsedBare_flagged() {
    helper
        .addSourceLines(
            "Test.java",
            "import static java.lang.Math.PI;",
            "class Test {",
            "  double f() {",
            "    // BUG: Diagnostic contains: ForbidJavaLangMath",
            "    return PI;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void methodReference_flagged() {
    helper
        .addSourceLines(
            "Test.java",
            "import java.util.function.LongBinaryOperator;",
            "class Test {",
            "  LongBinaryOperator f() {",
            "    // BUG: Diagnostic contains: ForbidJavaLangMath",
            "    return Math::max;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void fullyQualifiedMethodReference_flagged() {
    helper
        .addSourceLines(
            "Test.java",
            "import java.util.function.LongUnaryOperator;",
            "class Test {",
            "  LongUnaryOperator f() {",
            "    // BUG: Diagnostic contains: ForbidJavaLangMath",
            "    return java.lang.Math::abs;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void fieldSelect_flagged() {
    helper
        .addSourceLines(
            "Test.java",
            "class Test {",
            "  double f() {",
            "    // BUG: Diagnostic contains: ForbidJavaLangMath",
            "    return Math.PI;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void classLiteral_reflectionBackDoor_flagged() {
    helper
        .addSourceLines(
            "Test.java",
            "class Test {",
            "  Class<?> f() {",
            "    // BUG: Diagnostic contains: ForbidJavaLangMath",
            "    return Math.class;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void random_flagged() {
    helper
        .addSourceLines(
            "Test.java",
            "class Test {",
            "  double f() {",
            "    // BUG: Diagnostic contains: ForbidJavaLangMath",
            "    return Math.random();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void constantInitializer_flagged() {
    helper
        .addSourceLines(
            "Test.java",
            "class Test {",
            "  // BUG: Diagnostic contains: ForbidJavaLangMath",
            "  static final double TWO_PI = Math.PI * 2;",
            "}")
        .doTest();
  }

  @Test
  public void arrayInitializer_flagged() {
    helper
        .addSourceLines(
            "Test.java",
            "class Test {",
            "  // BUG: Diagnostic contains: ForbidJavaLangMath",
            "  static final double[] CONSTS = {Math.E};",
            "}")
        .doTest();
  }

  @Test
  public void annotationElementValue_flagged() {
    helper
        .addSourceLines(
            "Ann.java",
            "@interface Ann {",
            "  double value();",
            "}")
        .addSourceLines(
            "Test.java",
            "class Test {",
            "  // BUG: Diagnostic contains: ForbidJavaLangMath",
            "  @Ann(Math.PI)",
            "  void f() {}",
            "}")
        .doTest();
  }

  @Test
  public void insideAnonymousClass_flagged() {
    helper
        .addSourceLines(
            "Test.java",
            "class Test {",
            "  Runnable f() {",
            "    return new Runnable() {",
            "      public void run() {",
            "        // BUG: Diagnostic contains: ForbidJavaLangMath",
            "        double unused = Math.abs(-1.0);",
            "      }",
            "    };",
            "  }",
            "}")
        .doTest();
  }

  // ---------- negative: must NOT be flagged ----------

  @Test
  public void strictMath_ok() {
    // StrictMath is the deterministic primitive StrictMathWrapper is built on; never flag it.
    helper
        .addSourceLines(
            "Test.java",
            "import java.util.function.DoubleBinaryOperator;",
            "class Test {",
            "  double pow(double a, double b) {",
            "    return StrictMath.pow(a, b);",
            "  }",
            "  double pi() {",
            "    return StrictMath.PI;",
            "  }",
            "  DoubleBinaryOperator ref() {",
            "    return StrictMath::pow;",
            "  }",
            "  Class<?> literal() {",
            "    return StrictMath.class;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void userDefinedMathClass_ok() {
    // A user class merely named Math must not be confused with java.lang.Math. This is exactly
    // what the regex-based math-check.yml scan could not distinguish.
    helper
        .addSourceLines(
            "pkg/Math.java",
            "package pkg;",
            "public class Math {",
            "  public static final double PI = 3.0;",
            "  public static long max(long a, long b) {",
            "    return a > b ? a : b;",
            "  }",
            "}")
        .addSourceLines(
            "Test.java",
            "import pkg.Math;",
            "class Test {",
            "  long f(long a, long b) {",
            "    return Math.max(a, b);",
            "  }",
            "  double pi() {",
            "    return Math.PI;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void unrelatedMemberNamedLikeMathMember_ok() {
    helper
        .addSourceLines(
            "Test.java",
            "class Test {",
            "  static final double PI = 3.0;",
            "  double max(double a, double b) {",
            "    return a > b ? a : b;",
            "  }",
            "  double f() {",
            "    return max(PI, 1.0);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void mathInStringOrCommentOnly_ok() {
    // The old regex scan flagged these; a symbol-based checker must not.
    helper
        .addSourceLines(
            "Test.java",
            "class Test {",
            "  // Math.max is mentioned here on purpose.",
            "  String f() {",
            "    return \"Math.max(a, b)\";",
            "  }",
            "}")
        .doTest();
  }

  // ---------- escape hatch ----------

  @Test
  public void suppressWarnings_suppressesTheCheck() {
    // Documents the only available exemption. Note that no production code currently uses it:
    // StrictMathWrapper delegates to java.lang.StrictMath, so it needs no suppression.
    helper
        .addSourceLines(
            "Test.java",
            "class Test {",
            "  @SuppressWarnings(\"ForbidJavaLangMath\")",
            "  long f(long a, long b) {",
            "    return Math.max(a, b);",
            "  }",
            "}")
        .doTest();
  }
}
