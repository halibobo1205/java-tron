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
            "    // BUG: Diagnostic contains: Do not construct BigDecimal from a floating-point",
            "    new BigDecimal(doubleValue);",
            "    // BUG: Diagnostic contains: Do not construct BigDecimal from a floating-point",
            "    new BigDecimal(floatValue);",
            "    // BUG: Diagnostic contains: Do not construct BigDecimal from a floating-point",
            "    new BigDecimal(0.0001);",
            "    // BUG: Diagnostic contains: Do not construct BigDecimal from a floating-point",
            "    new BigDecimal(0.0001f, MathContext.DECIMAL64);",
            "  }",
            "  void boxed(Double doubleValue, Float floatValue) {",
            "    // BUG: Diagnostic contains: Do not construct BigDecimal from a floating-point",
            "    new BigDecimal(doubleValue);",
            "    // BUG: Diagnostic contains: Do not construct BigDecimal from a floating-point",
            "    new BigDecimal(floatValue, MathContext.DECIMAL64);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void rejectsConstructorReferences() {
    compilationHelper
        .addSourceLines(
            "Test.java",
            "import java.math.BigDecimal;",
            "import java.util.function.DoubleFunction;",
            "import java.util.stream.DoubleStream;",
            "import java.util.stream.Stream;",
            "class Test {",
            "  DoubleFunction<BigDecimal> reference() {",
            "    // BUG: Diagnostic contains: Do not construct BigDecimal from a floating-point",
            "    return BigDecimal::new;",
            "  }",
            "  Stream<BigDecimal> stream(DoubleStream values) {",
            "    // BUG: Diagnostic contains: Do not construct BigDecimal from a floating-point",
            "    return values.mapToObj(BigDecimal::new);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void rejectsAnonymousSubclassWithFloatingPointArgument() {
    compilationHelper
        .addSourceLines(
            "Test.java",
            "import java.math.BigDecimal;",
            "class Test {",
            "  BigDecimal anonymous() {",
            "    // BUG: Diagnostic contains: Do not construct BigDecimal from a floating-point",
            "    return new BigDecimal(0.5) { };",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void rejectsNamedSubclassDelegatingToFloatingPointConstructor() {
    compilationHelper
        .addSourceLines(
            "MyDecimal.java",
            "import java.math.BigDecimal;",
            "class MyDecimal extends BigDecimal {",
            "  MyDecimal(double value) {",
            "    // BUG: Diagnostic contains: Do not construct BigDecimal from a floating-point",
            "    super(value);",
            "  }",
            "  MyDecimal(String value) {",
            "    super(value);",
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
            "import java.util.function.Function;",
            "class Test {",
            "  void safe(String value, BigInteger integer, long longValue) {",
            "    new BigDecimal(value);",
            "    new BigDecimal(\"0.0001\");",
            "    new BigDecimal(integer);",
            "    new BigDecimal(longValue);",
            "    BigDecimal.valueOf(0.0001);",
            "  }",
            "  Function<String, BigDecimal> reference() {",
            "    return BigDecimal::new;",
            "  }",
            "}")
        .doTest();
  }
}
