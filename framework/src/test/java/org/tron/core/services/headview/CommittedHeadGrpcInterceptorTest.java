package org.tron.core.services.headview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.Attributes;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.tron.api.WalletGrpc;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.capsule.BlockCapsule.BlockId;
import org.tron.core.db.Manager;
import org.tron.core.db2.view.HeadLease;
import org.tron.core.db2.view.HeadView;
import org.tron.core.db2.view.HeadViewException;
import org.tron.core.db2.view.HeadViewException.Reason;
import org.tron.core.db2.view.HeadViewRegistry;
import org.tron.core.db2.view.QueryViewScope;
import org.tron.protos.Protocol.Account;
import org.tron.protos.Protocol.BlockHeader;

public class CommittedHeadGrpcInterceptorTest {

  private static final List<MethodDescriptor<?, ?>> PILOT_METHODS = Arrays.asList(
      WalletGrpc.getGetAccountMethod(),
      WalletGrpc.getGetAccountResourceMethod(),
      WalletGrpc.getGetContractMethod(),
      WalletGrpc.getGetContractInfoMethod(),
      WalletGrpc.getTriggerConstantContractMethod());

  private HeadViewRegistry registry;
  private CommittedHeadGrpcInterceptor interceptor;

  @Before
  public void setUp() throws Exception {
    registry = new HeadViewRegistry(2);
    long generation = registry.currentGeneration();
    registry.publish(new HeadView(new BlockId(Sha256Hash.ZERO_HASH, 7),
        BlockHeader.getDefaultInstance(), Collections.emptyMap()), generation);
    registry.markReady();
    Manager manager = mock(Manager.class);
    when(manager.getHeadViewRegistry()).thenReturn(registry);
    CommittedHeadQuery query = new CommittedHeadQuery();
    Field field = CommittedHeadQuery.class.getDeclaredField("manager");
    field.setAccessible(true);
    field.set(query, manager);
    interceptor = query.grpcInterceptor();
  }

  @After
  public void noScopeLeak() {
    assertNull(QueryViewScope.current());
  }

  @Test
  public void successSendsResolvedMetadataAfterSeal() {
    RecordingCall call = new RecordingCall(WalletGrpc.getGetAccountMethod());
    ServerCall.Listener<Account> listener = intercept(call, selector("committed-head"),
        c -> {
          assertTrue(QueryViewScope.isBound());
          c.sendHeaders(new Metadata());
          c.sendMessage(Account.getDefaultInstance());
          assertNull(call.headers);
          c.close(Status.OK, new Metadata());
        });
    listener.onMessage(Account.getDefaultInstance());
    listener.onHalfClose();

    assertTrue(call.status.isOk());
    assertEquals("7", call.headers.get(CommittedHeadGrpcInterceptor.BLOCK_NUMBER));
    assertSame(Account.getDefaultInstance(), call.message);
  }

  @Test
  public void queryFaultClosesUnavailableEvenWhenTheHandlerThrows() {
    RecordingCall call = new RecordingCall(WalletGrpc.getGetAccountMethod());
    ServerCall.Listener<Account> listener = intercept(call, selector("committed-head"),
        c -> {
          throw QueryViewScope.current().fail(Reason.EXPIRED, "expired");
        });
    listener.onMessage(Account.getDefaultInstance());
    listener.onHalfClose();

    assertEquals(Status.Code.UNAVAILABLE, call.status.getCode());
    assertEquals("HEAD_VIEW_EXPIRED", call.trailers.get(CommittedHeadGrpcInterceptor.ERROR));
    assertNull(call.message);
    assertLeaseReleased();
  }

  @Test
  public void swallowedFaultIsNotSentAsSuccess() {
    RecordingCall call = new RecordingCall(WalletGrpc.getGetAccountMethod());
    ServerCall.Listener<Account> listener = intercept(call, selector("committed-head"),
        c -> {
          try {
            throw QueryViewScope.current().unsupported("range read");
          } catch (HeadViewException ignored) {
            c.sendHeaders(new Metadata());
            c.sendMessage(Account.getDefaultInstance());
            c.close(Status.OK, new Metadata());
          }
        });
    listener.onMessage(Account.getDefaultInstance());
    listener.onHalfClose();

    assertEquals(Status.Code.INVALID_ARGUMENT, call.status.getCode());
    assertNull(call.message);
  }

  @Test
  public void unknownMethodAndValueAreRejectedAndMissingSelectorPassesThrough() {
    RecordingCall nonPilot = new RecordingCall(WalletGrpc.getGetNodeInfoMethod());
    intercept(nonPilot, selector("committed-head"), c -> { });
    assertEquals(Status.Code.INVALID_ARGUMENT, nonPilot.status.getCode());

    RecordingCall badValue = new RecordingCall(WalletGrpc.getGetAccountMethod());
    intercept(badValue, selector("latest"), c -> { });
    assertEquals(Status.Code.INVALID_ARGUMENT, badValue.status.getCode());

    RecordingCall plain = new RecordingCall(WalletGrpc.getGetAccountMethod());
    ServerCall.Listener<Account> listener = intercept(plain, new Metadata(), c -> {
      assertFalse(QueryViewScope.isBound());
      c.sendHeaders(new Metadata());
      c.close(Status.OK, new Metadata());
    });
    listener.onMessage(Account.getDefaultInstance());
    listener.onHalfClose();
    assertTrue(plain.status.isOk());
    assertNull(plain.headers.get(CommittedHeadGrpcInterceptor.BLOCK_NUMBER));
  }

  @Test
  public void everyPilotMethodIsAccepted() {
    for (MethodDescriptor<?, ?> method : PILOT_METHODS) {
      RecordingCall call = new RecordingCall(method);
      ServerCall.Listener<Account> listener = intercept(call, selector("committed-head"),
          c -> {
            assertTrue(QueryViewScope.isBound());
            c.sendHeaders(new Metadata());
            c.sendMessage(Account.getDefaultInstance());
            c.close(Status.OK, new Metadata());
          });
      listener.onMessage(Account.getDefaultInstance());
      listener.onHalfClose();
      assertTrue(method.getFullMethodName(), call.status.isOk());
      assertEquals("7", call.headers.get(CommittedHeadGrpcInterceptor.BLOCK_NUMBER));
    }
  }

  @Test
  public void duplicateSelectorIsRejected() {
    Metadata headers = selector("committed-head");
    headers.put(CommittedHeadGrpcInterceptor.SELECTOR, "committed-head");
    RecordingCall call = new RecordingCall(WalletGrpc.getGetAccountMethod());
    intercept(call, headers, c -> {
      throw new AssertionError("handler must not run");
    });
    assertEquals(Status.Code.INVALID_ARGUMENT, call.status.getCode());
  }

  @Test
  public void faultedLeaseOverridesAnErrorClose() {
    RecordingCall call = new RecordingCall(WalletGrpc.getGetAccountMethod());
    ServerCall.Listener<Account> listener = intercept(call, selector("committed-head"),
        c -> {
          try {
            throw QueryViewScope.current().fail(Reason.EXPIRED, "expired");
          } catch (HeadViewException e) {
            c.close(Status.INTERNAL.withDescription("handler failure"), new Metadata());
          }
        });
    listener.onMessage(Account.getDefaultInstance());
    listener.onHalfClose();
    assertEquals(Status.Code.UNAVAILABLE, call.status.getCode());
    assertEquals("HEAD_VIEW_EXPIRED", call.trailers.get(CommittedHeadGrpcInterceptor.ERROR));
  }

  @Test
  public void unavailableRegistryClosesWithoutCallingTheHandler() {
    registry.beginClosing();
    RecordingCall call = new RecordingCall(WalletGrpc.getGetAccountMethod());
    ServerCall.Listener<Account> listener = intercept(call, selector("committed-head"),
        c -> {
          throw new AssertionError("handler must not run");
        });
    listener.onMessage(Account.getDefaultInstance());
    listener.onHalfClose();
    assertEquals(Status.Code.UNAVAILABLE, call.status.getCode());
    assertEquals("HEAD_VIEW_UNAVAILABLE", call.trailers.get(CommittedHeadGrpcInterceptor.ERROR));
  }

  private void assertLeaseReleased() {
    HeadLease first = registry.acquire();
    HeadLease second = registry.acquire();
    first.close();
    second.close();
  }

  private ServerCall.Listener<Account> intercept(RecordingCall call, Metadata headers,
      Consumer<ServerCall<Account, Account>> handler) {
    ServerCallHandler<Account, Account> next = (serverCall, metadata) ->
        new ServerCall.Listener<Account>() {
          @Override
          public void onHalfClose() {
            handler.accept(serverCall);
          }
        };
    return interceptor.interceptCall(call, headers, next);
  }

  private static Metadata selector(String value) {
    Metadata metadata = new Metadata();
    metadata.put(CommittedHeadGrpcInterceptor.SELECTOR, value);
    return metadata;
  }

  private static final class RecordingCall extends ServerCall<Account, Account> {

    private final MethodDescriptor<Account, Account> method;
    private Metadata headers;
    private Account message;
    private Status status;
    private Metadata trailers;

    @SuppressWarnings("unchecked")
    RecordingCall(MethodDescriptor<?, ?> method) {
      this.method = (MethodDescriptor<Account, Account>) method;
    }

    @Override
    public void request(int numMessages) {
    }

    @Override
    public void sendHeaders(Metadata headers) {
      this.headers = headers;
    }

    @Override
    public void sendMessage(Account message) {
      this.message = message;
    }

    @Override
    public void close(Status status, Metadata trailers) {
      this.status = status;
      this.trailers = trailers;
    }

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public Attributes getAttributes() {
      return Attributes.EMPTY;
    }

    @Override
    public MethodDescriptor<Account, Account> getMethodDescriptor() {
      return method;
    }
  }
}
