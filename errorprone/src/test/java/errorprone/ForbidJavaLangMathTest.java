package errorprone;

import com.google.errorprone.CompilationTestHelper;
import org.junit.Test;

public class ForbidJavaLangMathTest {

  private final CompilationTestHelper compilationHelper =
      CompilationTestHelper.newInstance(ForbidJavaLangMath.class, getClass());

  @Test
  public void rejectsJavaLangMathUsage() {
    compilationHelper
        .addSourceLines(
            "Test.java",
            "import static java.lang.Math.E;",
            "import static java.lang.Math.max;",
            "import static java.lang.Math.*;",
            "import java.util.function.DoubleBinaryOperator;",
            "import java.util.function.DoubleUnaryOperator;",
            "class Test {",
            "  double directCall() {",
            "    // BUG: Diagnostic contains: Direct use of java.lang.Math is forbidden",
            "    return Math.sin(1.0);",
            "  }",
            "  double fullyQualifiedCall() {",
            "    // BUG: Diagnostic contains: Direct use of java.lang.Math is forbidden",
            "    return java.lang.Math.cos(1.0);",
            "  }",
            "  double staticallyImportedCall() {",
            "    // BUG: Diagnostic contains: Direct use of java.lang.Math is forbidden",
            "    return max(1.0, 2.0);",
            "  }",
            "  DoubleUnaryOperator methodReference() {",
            "    // BUG: Diagnostic contains: Direct use of java.lang.Math is forbidden",
            "    return Math::sin;",
            "  }",
            "  DoubleBinaryOperator fullyQualifiedMethodReference() {",
            "    // BUG: Diagnostic contains: Direct use of java.lang.Math is forbidden",
            "    return java.lang.Math::max;",
            "  }",
            "  double fieldAccess() {",
            "    // BUG: Diagnostic contains: Direct use of java.lang.Math is forbidden",
            "    return Math.PI;",
            "  }",
            "  double staticallyImportedField() {",
            "    // BUG: Diagnostic contains: Direct use of java.lang.Math is forbidden",
            "    return E;",
            "  }",
            "  Class<?> classLiteral() {",
            "    // BUG: Diagnostic contains: Direct use of java.lang.Math is forbidden",
            "    return Math.class;",
            "  }",
            "  double wildcardStaticImportCall() {",
            "    // BUG: Diagnostic contains: Direct use of java.lang.Math is forbidden",
            "    return floor(1.5);",
            "  }",
            "  double instanceQualifiedCall() {",
            "    Math instance = null;",
            "    // BUG: Diagnostic contains: Direct use of java.lang.Math is forbidden",
            "    return instance.sin(1.0);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void allowsUnrelatedMathStrictMathAndSuppression() {
    compilationHelper
        .addSourceLines(
            "Test.java",
            "import java.util.function.DoubleUnaryOperator;",
            "class Math {",
            "  static final double PI = 3.0;",
            "  static double sin(double value) { return value; }",
            "}",
            "class Test {",
            "  double unrelatedMath() {",
            "    DoubleUnaryOperator operator = Math::sin;",
            "    Class<?> type = Math.class;",
            "    return operator.applyAsDouble(Math.PI);",
            "  }",
            "  double strictMath() {",
            "    return java.lang.StrictMath.sin(StrictMath.PI);",
            "  }",
            "  @SuppressWarnings(\"ForbidJavaLangMath\")",
            "  double suppressed() {",
            "    return java.lang.Math.sin(Math.PI);",
            "  }",
            "  String text() {",
            "    // Math.sin(1.0) in a comment is not a usage.",
            "    return \"java.lang.Math.cos(1.0)\";",
            "  }",
            "  Class<?> reflection() throws Exception {",
            "    // String-based reflection is out of scope, matching the old regex scan.",
            "    return Class.forName(\"java.lang.Math\");",
            "  }",
            "}")
        .doTest();
  }
}
