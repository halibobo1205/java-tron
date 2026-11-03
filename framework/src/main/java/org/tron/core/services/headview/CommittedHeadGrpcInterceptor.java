package org.tron.core.services.headview;

import com.google.common.collect.ImmutableSet;
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.Iterator;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.tron.api.WalletGrpc;
import org.tron.common.utils.ByteArray;
import org.tron.core.db2.view.HeadLease;
import org.tron.core.db2.view.HeadView;
import org.tron.core.db2.view.HeadViewException;
import org.tron.core.db2.view.HeadViewException.Reason;
import org.tron.core.db2.view.QueryViewScope;

@Slf4j(topic = "API")
public final class CommittedHeadGrpcInterceptor implements ServerInterceptor {

  public static final Metadata.Key<String> SELECTOR =
      Metadata.Key.of("tron-state-view", Metadata.ASCII_STRING_MARSHALLER);
  public static final Metadata.Key<String> BLOCK_NUMBER =
      Metadata.Key.of("tron-block-number", Metadata.ASCII_STRING_MARSHALLER);
  public static final Metadata.Key<String> BLOCK_ID =
      Metadata.Key.of("tron-block-id", Metadata.ASCII_STRING_MARSHALLER);
  public static final Metadata.Key<String> ERROR =
      Metadata.Key.of("tron-state-view-error", Metadata.ASCII_STRING_MARSHALLER);

  private static final Set<String> PILOT = ImmutableSet.of(
      WalletGrpc.getGetAccountMethod().getFullMethodName(),
      WalletGrpc.getGetAccountResourceMethod().getFullMethodName(),
      WalletGrpc.getGetContractMethod().getFullMethodName(),
      WalletGrpc.getGetContractInfoMethod().getFullMethodName(),
      WalletGrpc.getTriggerConstantContractMethod().getFullMethodName());

  private final CommittedHeadQuery query;

  CommittedHeadGrpcInterceptor(CommittedHeadQuery query) {
    this.query = query;
  }

  @Override
  public <Q, A> ServerCall.Listener<Q> interceptCall(ServerCall<Q, A> call, Metadata headers,
      ServerCallHandler<Q, A> next) {
    Iterable<String> selectors = headers.getAll(SELECTOR);
    if (selectors == null) {
      return next.startCall(call, headers);
    }
    String method = call.getMethodDescriptor().getFullMethodName();
    if (!isSingleCommittedHeadSelector(selectors) || !PILOT.contains(method)) {
      call.close(statusOf(Reason.UNSUPPORTED), trailersOf(Reason.UNSUPPORTED));
      return new ServerCall.Listener<Q>() {
      };
    }
    BufferedCall<Q, A> buffered = new BufferedCall<>(call);
    return new HeadListener<>(next.startCall(buffered, headers), buffered, query);
  }

  private static boolean isSingleCommittedHeadSelector(Iterable<String> selectors) {
    Iterator<String> values = selectors.iterator();
    if (!values.hasNext()) {
      return false;
    }
    String value = values.next();
    return CommittedHeadQuery.COMMITTED_HEAD.equals(value) && !values.hasNext();
  }

  static Status statusOf(Reason reason) {
    Reason effective = reason == null ? Reason.UNAVAILABLE : reason;
    Status status;
    switch (effective) {
      case UNSUPPORTED:
        status = Status.INVALID_ARGUMENT;
        break;
      case RESPONSE_TOO_LARGE:
        status = Status.RESOURCE_EXHAUSTED;
        break;
      default:
        status = Status.UNAVAILABLE;
    }
    return status.withDescription(effective.getCode());
  }

  static Metadata trailersOf(Reason reason) {
    Metadata trailers = new Metadata();
    trailers.put(ERROR, (reason == null ? Reason.UNAVAILABLE : reason).getCode());
    return trailers;
  }

  private static final class HeadListener<Q, A> extends SimpleForwardingServerCallListener<Q> {

    private final BufferedCall<Q, A> call;
    private final CommittedHeadQuery query;
    private int messages;

    HeadListener(ServerCall.Listener<Q> delegate, BufferedCall<Q, A> call,
        CommittedHeadQuery query) {
      super(delegate);
      this.call = call;
      this.query = query;
    }

    @Override
    public void onMessage(Q message) {
      messages++;
      super.onMessage(message);
    }

    @Override
    public void onHalfClose() {
      if (messages != 1) {
        super.onHalfClose();
        return;
      }
      HeadLease lease;
      try {
        lease = query.registry().acquire();
      } catch (HeadViewException e) {
        call.failAndClose(e.getReason());
        return;
      }
      call.attach(lease);
      try (QueryViewScope ignored = QueryViewScope.bind(lease)) {
        super.onHalfClose();
      } catch (HeadViewException e) {
        lease.fail(e.getReason(), e.getMessage());
        call.failAndClose(lease.getFault());
      } catch (RuntimeException e) {
        if (lease.getFault() == null) {
          throw e;
        }
        logger.debug("Committed head request failed after a view fault", e);
        call.failAndClose(lease.getFault());
      } finally {
        lease.close();
      }
    }
  }

  private static final class BufferedCall<Q, A> extends SimpleForwardingServerCall<Q, A> {

    private Metadata pendingHeaders;
    private A pendingMessage;
    private boolean hasMessage;
    private boolean closed;
    private HeadLease lease;

    BufferedCall(ServerCall<Q, A> delegate) {
      super(delegate);
    }

    void attach(HeadLease lease) {
      this.lease = lease;
    }

    @Override
    public void sendHeaders(Metadata headers) {
      if (pendingHeaders == null) {
        pendingHeaders = new Metadata();
      }
      pendingHeaders.merge(headers);
    }

    @Override
    public void sendMessage(A message) {
      if (hasMessage) {
        if (lease != null) {
          lease.fail(Reason.UNSUPPORTED, "more than one response message");
        }
        return;
      }
      pendingMessage = message;
      hasMessage = true;
    }

    @Override
    public void close(Status status, Metadata trailers) {
      if (closed) {
        return;
      }
      if (!status.isOk()) {
        if (lease != null && lease.getFault() != null) {
          failAndClose(lease.getFault());
          return;
        }
        closed = true;
        super.close(status, trailers);
        return;
      }
      if (lease == null || !lease.seal()) {
        failAndClose(lease == null ? Reason.UNAVAILABLE : lease.getFault());
        return;
      }
      closed = true;
      Metadata headers = pendingHeaders == null ? new Metadata() : pendingHeaders;
      HeadView view = lease.getView();
      headers.put(BLOCK_NUMBER, Long.toString(view.getBlockNum()));
      headers.put(BLOCK_ID, ByteArray.toHexString(view.getBlockId().getBytes()));
      super.sendHeaders(headers);
      if (hasMessage) {
        super.sendMessage(pendingMessage);
      }
      super.close(status, trailers);
    }

    void failAndClose(Reason reason) {
      if (closed) {
        return;
      }
      closed = true;
      pendingMessage = null;
      hasMessage = false;
      super.close(statusOf(reason), trailersOf(reason));
    }
  }
}
