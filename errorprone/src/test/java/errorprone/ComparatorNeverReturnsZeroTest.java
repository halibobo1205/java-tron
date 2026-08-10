package errorprone;

import com.google.errorprone.CompilationTestHelper;
import org.junit.Test;

/** Tests for {@link ComparatorNeverReturnsZero}. */
public class ComparatorNeverReturnsZeroTest {

  private final CompilationTestHelper helper =
      CompilationTestHelper.newInstance(ComparatorNeverReturnsZero.class, getClass());

  // ---------- positive: must be flagged ----------

  @Test
  public void lambdaExpression_leEver1Else1_flagged() {
    helper
        .addSourceLines(
            "Test.java",
            "import java.util.List;",
            "class Test {",
            "  void f(List<Long> xs) {",
            "    // BUG: Diagnostic contains: ComparatorNeverReturnsZero",
            "    xs.sort((a, b) -> a <= b ? 1 : -1);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void lambdaExpression_gtThen1Else1_flagged() {
    helper
        .addSourceLines(
            "Test.java",
            "import java.util.List;",
            "class Test {",
            "  void f(List<Long> xs) {",
            "    // BUG: Diagnostic contains: ComparatorNeverReturnsZero",
            "    xs.sort((a, b) -> a > b ? 1 : -1);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void lambdaBlock_allReturnsNonZero_flagged() {
    helper
        .addSourceLines(
            "Test.java",
            "import java.util.Comparator;",
            "class Test {",
            "  Comparator<Long> c() {",
            "    // BUG: Diagnostic contains: ComparatorNeverReturnsZero",
            "    return (a, b) -> {",
            "      if (a <= b) {",
            "        return 1;",
            "      }",
            "      return -1;",
            "    };",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void anonymousComparator_compare_flagged() {
    helper
        .addSourceLines(
            "Test.java",
            "import java.util.Comparator;",
            "class Test {",
            "  Comparator<Long> c() {",
            "    return new Comparator<Long>() {",
            "      // BUG: Diagnostic contains: ComparatorNeverReturnsZero",
            "      public int compare(Long a, Long b) {",
            "        return a <= b ? 1 : -1;",
            "      }",
            "    };",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void namedComparable_compareTo_flagged() {
    helper
        .addSourceLines(
            "Test.java",
            "class Test implements Comparable<Test> {",
            "  long t;",
            "  // BUG: Diagnostic contains: ComparatorNeverReturnsZero",
            "  public int compareTo(Test o) {",
            "    return this.t < o.t ? -1 : 1;",
            "  }",
            "}")
        .doTest();
  }

  // ---------- negative: must NOT be flagged ----------

  @Test
  public void lambdaLongCompare_ok() {
    helper
        .addSourceLines(
            "Test.java",
            "import java.util.List;",
            "class Test {",
            "  void f(List<Long> xs) {",
            "    xs.sort((a, b) -> Long.compare(a, b));",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void lambdaComparingLong_ok() {
    helper
        .addSourceLines(
            "Test.java",
            "import java.util.Comparator;",
            "import java.util.List;",
            "class Test {",
            "  void f(List<Test> xs) {",
            "    xs.sort(Comparator.comparingLong((Test t) -> t.t).reversed());",
            "  }",
            "  long t;",
            "}")
        .doTest();
  }

  @Test
  public void ternaryCanReturnZero_ok() {
    helper
        .addSourceLines(
            "Test.java",
            "import java.util.List;",
            "class Test {",
            "  void f(List<Long> xs) {",
            "    xs.sort((a, b) -> a <= b ? 1 : 0);", // can yield 0 -> not a violation shape
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void ceilingDivisionNotAComparator_ok() {
    helper
        .addSourceLines(
            "Test.java",
            "class Test {",
            "  long ceil(long numerator, long denominator) {",
            "    return (numerator / denominator) + ((numerator % denominator) > 0 ? 1 : 0);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void compareToUsingLongCompare_ok() {
    helper
        .addSourceLines(
            "Test.java",
            "class Test implements Comparable<Test> {",
            "  long t;",
            "  public int compareTo(Test o) {",
            "    return Long.compare(this.t, o.t);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void compareToWithNullGuardAndRealCompare_ok() {
    // Mirrors DataWord.compareTo: one constant return (-1 for null) plus a non-constant return.
    helper
        .addSourceLines(
            "Test.java",
            "class Test implements Comparable<Test> {",
            "  long t;",
            "  public int compareTo(Test o) {",
            "    if (o == null) {",
            "      return -1;",
            "    }",
            "    return Long.compare(this.t, o.t);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void overloadedCompareToNotAnOverride_ok() {
    // compareTo(long) is an overload, not an override of Comparable.compareTo(Test); must not flag.
    helper
        .addSourceLines(
            "Test.java",
            "class Test implements Comparable<Test> {",
            "  long t;",
            "  public int compareTo(Test o) {",
            "    return Long.compare(this.t, o.t);",
            "  }",
            "  int compareTo(long value) {",
            "    return value < 0 ? -1 : 1;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void twoArgStaticCompareOverloadInComparator_ok() {
    // static compare(long,long) is a 2-arg overload inside a Comparator class but overrides
    // nothing. The old name+arity+owner rule would flag it, the override-based rule must not.
    helper
        .addSourceLines(
            "Test.java",
            "import java.util.Comparator;",
            "class Test implements Comparator<Long> {",
            "  public int compare(Long a, Long b) {",
            "    return Long.compare(a, b);",
            "  }",
            "  static int compare(long a, long b) {",
            "    return a < b ? -1 : 1;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void twoArgPrivateCompareOverloadInComparator_ok() {
    // private compare(long,long) is a 2-arg overload inside a Comparator class but overrides
    // nothing. The old name+arity+owner rule would flag it, the override-based rule must not.
    helper
        .addSourceLines(
            "Test.java",
            "import java.util.Comparator;",
            "class Test implements Comparator<Long> {",
            "  public int compare(Long a, Long b) {",
            "    return Long.compare(a, b);",
            "  }",
            "  private int compare(long a, long b) {",
            "    return a < b ? -1 : 1;",
            "  }",
            "}")
        .doTest();
  }
}
