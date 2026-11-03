package org.tron.core.services.headview;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Map;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.utils.ByteArray;
import org.tron.core.config.args.Args;
import org.tron.core.db.Manager;
import org.tron.core.db2.view.HeadLease;
import org.tron.core.db2.view.HeadView;
import org.tron.core.db2.view.HeadViewException;
import org.tron.core.db2.view.HeadViewException.Reason;
import org.tron.core.db2.view.HeadViewRegistry;
import org.tron.core.db2.view.QueryViewScope;
import org.tron.core.services.http.HttpApi.Surface;
import org.tron.core.services.http.HttpApiRegistry;

@Slf4j(topic = "API")
@Component
public class CommittedHeadQuery {

  public static final String COMMITTED_HEAD = "committed-head";
  public static final String HTTP_SELECTOR = "X-Tron-State-View";
  public static final String HTTP_BLOCK_NUMBER = "X-Tron-Block-Number";
  public static final String HTTP_BLOCK_ID = "X-Tron-Block-Id";

  @Autowired
  private Manager manager;

  public interface HttpInvocation {

    void invoke(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException;
  }

  public boolean isEnabled() {
    return registry() != null;
  }

  HeadViewRegistry registry() {
    return manager == null ? null : manager.getHeadViewRegistry();
  }

  public boolean isRequested(HttpServletRequest request, Surface declaredSurface) {
    return declaredSurface != null && request.getHeader(HTTP_SELECTOR) != null && isEnabled();
  }

  public CommittedHeadGrpcInterceptor grpcInterceptor() {
    return new CommittedHeadGrpcInterceptor(this);
  }

  public void serviceHttp(Class<?> servlet, Surface surface, HttpServletRequest request,
      HttpServletResponse response, HttpInvocation invocation)
      throws ServletException, IOException {
    if (!acceptsHttp(servlet, surface, request)) {
      writeHttpError(response, Reason.UNSUPPORTED);
      return;
    }
    byte[] body = ByteStreams.toByteArray(request.getInputStream());
    HttpServletRequest cached = new CachedBodyRequest(request, body);
    BufferedHttpResponse buffered = new BufferedHttpResponse(response,
        Args.getInstance().getCommittedHeadQueryMaxResponseSize());
    HeadLease lease;
    try {
      lease = registry().acquire();
    } catch (HeadViewException e) {
      writeHttpError(response, e.getReason());
      return;
    }
    boolean sealed;
    try {
      invokeHttp(lease, invocation, cached, buffered);
      if (buffered.isOverflow()) {
        lease.fail(Reason.RESPONSE_TOO_LARGE, "response exceeds the committed head buffer");
      }
      sealed = lease.seal();
    } finally {
      lease.close();
    }
    if (sealed) {
      buffered.commitTo(response, resolvedHttpHeaders(lease.getView()));
    } else {
      writeHttpError(response, lease.getFault());
    }
  }

  private static boolean acceptsHttp(Class<?> servlet, Surface surface,
      HttpServletRequest request) {
    String method = request.getMethod();
    return hasSingleCommittedHeadSelector(request)
        && surface == Surface.FULL
        && HttpApiRegistry.committedHeadServlets().contains(servlet)
        && ("GET".equals(method) || "POST".equals(method));
  }

  private static boolean hasSingleCommittedHeadSelector(HttpServletRequest request) {
    Enumeration<String> values = request.getHeaders(HTTP_SELECTOR);
    if (values == null || !values.hasMoreElements()) {
      return false;
    }
    String value = values.nextElement();
    return COMMITTED_HEAD.equals(value) && !values.hasMoreElements();
  }

  private static void invokeHttp(HeadLease lease, HttpInvocation invocation,
      HttpServletRequest request, BufferedHttpResponse response)
      throws ServletException, IOException {
    try (QueryViewScope ignored = QueryViewScope.bind(lease)) {
      invocation.invoke(request, response);
    } catch (HeadViewException e) {
      lease.fail(e.getReason(), e.getMessage());
    } catch (ServletException | IOException | RuntimeException e) {
      if (lease.getFault() == null) {
        throw e;
      }
    }
  }

  static Map<String, String> resolvedHttpHeaders(HeadView view) {
    return ImmutableMap.of(
        HTTP_BLOCK_NUMBER, Long.toString(view.getBlockNum()),
        HTTP_BLOCK_ID, ByteArray.toHexString(view.getBlockId().getBytes()));
  }

  private static int httpStatusOf(Reason reason) {
    switch (reason) {
      case UNSUPPORTED:
        return HttpServletResponse.SC_BAD_REQUEST;
      case RESPONSE_TOO_LARGE:
        return HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
      default:
        return HttpServletResponse.SC_SERVICE_UNAVAILABLE;
    }
  }

  static void writeHttpError(HttpServletResponse response, Reason reason) throws IOException {
    Reason effective = reason == null ? Reason.UNAVAILABLE : reason;
    response.setStatus(httpStatusOf(effective));
    response.setContentType("application/json; charset=utf-8");
    response.getWriter().println("{\"Error\":\"" + effective.getCode() + "\"}");
  }
}
