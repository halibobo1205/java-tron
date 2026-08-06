package errorprone;

import com.google.errorprone.CompilationTestHelper;
import org.junit.Test;

public class BigDecimalFloatingPointConstructorTest {

  private final CompilationTestHelper compilationHelper =
      CompilationTestHelper.newInstance(BigDecimalFloatingPointConstructor.class, getClass());

  @Test
  public void rejectsFloatingPointArguments() {
    compilationHelper
        .addSourceLines(
            "Test.java",
            "import java.math.BigDecimal;",
            "import java.math.MathContext;",
            "class Test {",
            "  void primitive(double doubleValue, float floatValue) {",
            "    // BUG: Diagnostic contains: Do not construct BigDecimal from a floating-point value",
            "    new BigDecimal(doubleValue);",
            "    // BUG: Diagnostic contains: Do not construct BigDecimal from a floating-point value",
            "    new BigDecimal(floatValue);",
            "    // BUG: Diagnostic contains: Do not construct BigDecimal from a floating-point value",
            "    new BigDecimal(0.0001);",
            "    // BUG: Diagnostic contains: Do not construct BigDecimal from a floating-point value",
            "    new BigDecimal(0.0001f, MathContext.DECIMAL64);",
            "  }",
            "  void boxed(Double doubleValue, Float floatValue) {",
            "    // BUG: Diagnostic contains: Do not construct BigDecimal from a floating-point value",
            "    new BigDecimal(doubleValue);",
            "    // BUG: Diagnostic contains: Do not construct BigDecimal from a floating-point value",
            "    new BigDecimal(floatValue, MathContext.DECIMAL64);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void allowsNonFloatingPointArguments() {
    compilationHelper
        .addSourceLines(
            "Test.java",
            "import java.math.BigDecimal;",
            "import java.math.BigInteger;",
            "class Test {",
            "  void safe(String value, BigInteger integer, long longValue) {",
            "    new BigDecimal(value);",
            "    new BigDecimal(\"0.0001\");",
            "    new BigDecimal(integer);",
            "    new BigDecimal(longValue);",
            "    BigDecimal.valueOf(0.0001);",
            "  }",
            "}")
        .doTest();
  }
}
