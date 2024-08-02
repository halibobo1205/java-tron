package org.tron.core.services.http;

import java.util.Arrays;
import java.util.EnumSet;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.Servlet;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.StringUtil;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.tron.common.application.HttpService;
import org.tron.core.config.args.Args;
import org.tron.core.services.annotation.FullServlet;
import org.tron.core.services.filter.HttpApiAccessFilter;
import org.tron.core.services.filter.HttpInterceptor;
import org.tron.core.services.filter.LiteFnQueryHttpFilter;


@Component("fullNodeHttpApiService")
@Slf4j(topic = "API")
public class FullNodeHttpApiService extends HttpService {

  @Autowired
  private ApplicationContext applicationContext;
  @Autowired
  private LiteFnQueryHttpFilter liteFnQueryHttpFilter;
  @Autowired
  private HttpApiAccessFilter httpApiAccessFilter;

  public FullNodeHttpApiService() {
    port = Args.getInstance().getFullNodeHttpPort();
    enable = isFullNode() && Args.getInstance().isFullNodeHttpEnable();
    contextPath = "/";
  }

  @Override
  protected void addServlet(ServletContextHandler context) {
    applicationContext.getBeansWithAnnotation(FullServlet.class).values()
        .stream().filter(o -> o instanceof Servlet)
        .map(o -> (Servlet) o)
        .filter(o -> AopUtils.getTargetClass(o).isAnnotationPresent(FullServlet.class))
        .forEach(o -> {
          FullServlet path = AopUtils.getTargetClass(o).getAnnotation(FullServlet.class);
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

    // http access filter, it should have higher priority than HttpInterceptor
    context.addFilter(new FilterHolder(httpApiAccessFilter), "/*",
        EnumSet.allOf(DispatcherType.class));
    // note: if the pathSpec of servlet is not started with wallet, it should be included here
    context.getServletHandler().getFilterMappings()[1]
        .setPathSpecs(new String[] {"/wallet/*",
            "/net/listnodes",
            "/monitor/getstatsinfo",
            "/monitor/getnodeinfo"});

    // metrics filter
    ServletHandler handler = new ServletHandler();
    FilterHolder fh = handler
        .addFilterWithMapping((Class<? extends Filter>) HttpInterceptor.class, "/*",
            EnumSet.of(DispatcherType.REQUEST));
    context.addFilter(fh, "/*", EnumSet.of(DispatcherType.REQUEST));
  }
}
