package org.tron.core.services.http.solidity;

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
import org.springframework.stereotype.Component;
import org.tron.common.application.HttpService;
import org.tron.core.config.args.Args;
import org.tron.core.services.annotation.SolidityNodeServlet;
import org.tron.core.services.filter.HttpApiAccessFilter;


@Component
@Slf4j(topic = "API")
public class SolidityNodeHttpApiService extends HttpService {

  @Autowired
  private ApplicationContext applicationContext;
  @Autowired
  private HttpApiAccessFilter httpApiAccessFilter;


  public SolidityNodeHttpApiService() {
    port = Args.getInstance().getSolidityHttpPort();
    enable = !isFullNode() && Args.getInstance().isSolidityNodeHttpEnable();
    contextPath = "/";
  }

  @Override
  protected void addServlet(ServletContextHandler context) {
    applicationContext.getBeansWithAnnotation(SolidityNodeServlet.class).values()
        .stream().filter(o -> o instanceof Servlet)
        .map(o -> (Servlet) o)
        .filter(o -> AopUtils.getTargetClass(o).isAnnotationPresent(SolidityNodeServlet.class))
        .forEach(o -> {
          SolidityNodeServlet path = AopUtils.getTargetClass(o)
              .getAnnotation(SolidityNodeServlet.class);
          Arrays.stream(path.value()).filter(StringUtil::isNotBlank).forEach(p ->
              context.addServlet(new ServletHolder(o), p));
        });
  }

  @Override
  protected void addFilter(ServletContextHandler context) {
    // http access filter
    context.addFilter(new FilterHolder(httpApiAccessFilter), "/walletsolidity/*",
        EnumSet.allOf(DispatcherType.class));
    context.getServletHandler().getFilterMappings()[0]
        .setPathSpecs(new String[] {"/walletsolidity/*",
            "/wallet/getnodeinfo"});
  }
}
