package org.tron.common.utils;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;

@Slf4j(topic = "grpcClient")
/**
 * A gRPC ClientInterceptor that provides deep, event-level logging to debug complex
 * concurrency and hanging issues. It meticulously logs the sequence of events across
 * the application thread and gRPC's internal I/O threads.
 */
public class DebugInterceptor implements ClientInterceptor {

  private final AtomicLong callIdCounter = new AtomicLong(0);

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
    logger.debug("callId={}, event=interceptCall, method={}, callOptions={}",
        callIdCounter.incrementAndGet(), method.getFullMethodName(), callOptions);
    ClientCall<ReqT, RespT> call = next.newCall(method, callOptions);
    return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(call) {

      @Override
      public void start(Listener<RespT> responseListener, Metadata headers) {
        logger.debug("callId={}, event=start, headers={}", callIdCounter.get(), headers);
        super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(
            responseListener) {
          @Override
          public void onHeaders(Metadata headers) {
            logger.debug("callId={}, event=onHeaders, headers={}", callIdCounter.get(), headers);
            try {
              super.onHeaders(headers);
              logger.debug("callId={}, event=onHeaders completed", callIdCounter.get());
            } catch (Exception e) {
              logger.error("callId={}, event=onHeaders, exception={}", callIdCounter.get(),
                  e.getMessage(), e);
              throw e;
            }
          }

          @Override
          public void onClose(Status status, Metadata trailers) {
            logger.debug("callId={}, event=onClose, status={}, trailers={}",
                callIdCounter.get(), status, trailers);
            try {
              super.onClose(status, trailers);
              logger.debug("callId={}, event=onClose completed", callIdCounter.get());
            } catch (Exception e) {
              logger.error("callId={}, event=onClose, exception={}", callIdCounter.get(),
                  e.getMessage(), e);
              throw e;
            }
          }

          @Override
          public void onMessage(RespT message) {
            logger.debug("callId={}, event=onMessage, message={}", callIdCounter.get(), message);
            try {
              super.onMessage(message);
              logger.debug("callId={}, event=onMessage completed", callIdCounter.get());
            } catch (Exception e) {
              logger.error("callId={}, event=onMessage, exception={}", callIdCounter.get(),
                  e.getMessage(), e);
              throw e;
            }
          }

          public void onReady() {
            logger.debug("callId={}, event=onReady", callIdCounter.get());
            try {
              super.onReady();
              logger.debug("callId={}, event=onReady completed", callIdCounter.get());
            } catch (Exception e) {
              logger.error("callId={}, event=onReady, exception={}", callIdCounter.get(),
                  e.getMessage(), e);
              throw e;
            }
          }

        }, headers);
        logger.debug("callId={}, event=start completed", callIdCounter.get());
      }
    };
  }
}