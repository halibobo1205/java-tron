package org.tron.core.archive.query;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import java.util.concurrent.FutureTask;
import org.junit.Test;

public class QueryContextHolderTest {

  @Test
  public void nestedScopesRestoreThePreviousContext() {
    QueryContext outer = new QueryContext(ArchiveQueryLimits.unlimited());
    QueryContext inner = new QueryContext(ArchiveQueryLimits.unlimited());

    try (QueryContextHolder.Scope outerScope = QueryContextHolder.attach(outer)) {
      assertSame(outer, QueryContextHolder.current());
      try (QueryContextHolder.Scope innerScope = QueryContextHolder.attach(inner)) {
        assertSame(inner, QueryContextHolder.current());
      }
      assertSame(outer, QueryContextHolder.current());
    }

    assertNull(QueryContextHolder.current());
    assertFalse(QueryContextHolder.isActive());
  }

  @Test
  public void nullCompatibilityScopePreservesAnOuterContext() {
    QueryContext context = new QueryContext(ArchiveQueryLimits.unlimited());

    try (QueryContextHolder.Scope outer = QueryContextHolder.attach(context);
        QueryContextHolder.Scope ignored = QueryContextHolder.attachIfPresent(null)) {
      assertSame(context, QueryContextHolder.current());
    }

    assertNull(QueryContextHolder.current());
  }

  @Test
  public void contextIsNotInheritedByAnotherThread() throws Exception {
    QueryContext context = new QueryContext(ArchiveQueryLimits.unlimited());
    FutureTask<QueryContext> otherThreadContext =
        new FutureTask<>(QueryContextHolder::current);

    try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(context)) {
      Thread thread = new Thread(otherThreadContext, "query-context-holder-test");
      thread.start();
      assertNull(otherThreadContext.get());
      assertSame(context, QueryContextHolder.current());
    }
  }

  @Test
  public void scopesMustCloseInLastInFirstOutOrder() {
    QueryContext context = new QueryContext(ArchiveQueryLimits.unlimited());
    QueryContextHolder.Scope outer = QueryContextHolder.attach(context);
    QueryContextHolder.Scope inner = QueryContextHolder.attach(context);

    assertThrows(IllegalStateException.class, outer::close);
    inner.close();
    outer.close();

    assertNull(QueryContextHolder.current());
  }
}
