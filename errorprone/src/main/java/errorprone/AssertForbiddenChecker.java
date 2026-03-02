package errorprone;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.sun.source.tree.AssertTree;


@AutoService(BugChecker.class)
@BugPattern(
    name = "AssertForbiddenChecker",
    summary = "Use of 'assert' is forbidden in production code. "
        + "Use explicit checks with exceptions "
        + "(e.g., Objects.requireNonNull, Preconditions.checkArgument) instead.",
    severity = BugPattern.SeverityLevel.ERROR
)
public class AssertForbiddenChecker extends BugChecker
    implements BugChecker.AssertTreeMatcher {

  @Override
  public Description matchAssert(AssertTree tree, VisitorState state) {
    return describeMatch(tree);
  }
}
