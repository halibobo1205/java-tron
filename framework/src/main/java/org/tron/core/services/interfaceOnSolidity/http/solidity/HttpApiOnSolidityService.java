package org.tron.core.services.interfaceOnSolidity.http.solidity;

import java.util.Arrays;
import java.util.EnumSet;
import javax.servlet.DispatcherType;
import javax.servlet.Servlet;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.StringUtil;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.tron.common.application.HttpService;
import org.tron.core.config.args.Args;
import org.tron.core.services.annotation.SolidityServlet;
import org.tron.core.services.filter.HttpApiAccessFilter;
import org.tron.core.services.filter.LiteFnQueryHttpFilter;

@Slf4j(topic = "API")
public class HttpApiOnSolidityService extends HttpService {

  @Autowired
  private ApplicationContext applicationContext;

  @Autowired
  private LiteFnQueryHttpFilter liteFnQueryHttpFilter;

  @Autowired
  private HttpApiAccessFilter httpApiAccessFilter;

  public HttpApiOnSolidityService() {
    port = Args.getInstance().getSolidityHttpPort();
    enable = isFullNode() && Args.getInstance().isSolidityNodeHttpEnable();
    contextPath = "/";
  }

  @Override
  protected void addServlet(ServletContextHandler context) {
    applicationContext.getBeansWithAnnotation(SolidityServlet.class).values()
        .stream().filter(o -> o instanceof Servlet)
        .map(o -> (Servlet) o)
        .filter(o -> AopUtils.getTargetClass(o).isAnnotationPresent(SolidityServlet.class))
        .forEach(o -> {
          SolidityServlet path = AopUtils.getTargetClass(o)
              .getAnnotation(SolidityServlet.class);
          Arrays.stream(path.value()).filter(StringUtil::isNotBlank).forEach(p ->
              context.addServlet(new ServletHolder(o), p));
        });

  }

  @Override
  protected void addFilter(ServletContextHandler context) {
    // filters the specified APIs
    // when node is lite fullnode and openHistoryQueryWhenLiteFN is false
    context.addFilter(new FilterHolder(liteFnQueryHttpFilter), "/*",
        EnumSet.allOf(DispatcherType.class));

    // api access filter
    context.addFilter(new FilterHolder(httpApiAccessFilter), "/walletsolidity/*",
        EnumSet.allOf(DispatcherType.class));
    context.getServletHandler().getFilterMappings()[1]
        .setPathSpecs(new String[] {"/walletsolidity/*",
            "/wallet/getnodeinfo"});
  }
}
