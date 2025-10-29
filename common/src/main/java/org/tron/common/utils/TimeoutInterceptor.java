package org.tron.common.utils;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.MethodDescriptor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

@Slf4j(topic = "grpc")
public class TimeoutInterceptor implements ClientInterceptor {

  private final long timeout;

  /**
   * @param timeout ms
   */
  public TimeoutInterceptor(long timeout) {
    this.timeout = timeout;
  }

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method,
      CallOptions callOptions,
      Channel next) {
    CallOptions withDeadline = callOptions.withDeadlineAfter(timeout, TimeUnit.MILLISECONDS);
    logger.debug("intercept call with {} for method {}", withDeadline, method.getFullMethodName());
    return next.newCall(method, withDeadline);
  }
}
