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
import lombok.extern.slf4j.Slf4j;

@Slf4j(topic = "grpcClient")
public class DebugInterceptor implements ClientInterceptor {
  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
    ClientCall<ReqT, RespT> call = next.newCall(method, callOptions);
    return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(call) {
      @Override
      public void start(Listener<RespT> responseListener, Metadata headers) {
        super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(
            responseListener) {
          @Override
          public void onHeaders(Metadata headers) {
            logger.debug("onHeaders: {}", headers);
            super.onHeaders(headers);
          }

          @Override
          public void onClose(Status status, Metadata trailers) {
            logger.debug("onClose: {}", status);
            super.onClose(status, trailers);
          }

          @Override
          public void onMessage(RespT message) {
            logger.debug("onMessage: {}", message);
            super.onMessage(message);
          }
        }, headers);
      }
    };
  }
}