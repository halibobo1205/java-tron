package org.tron.common.storage.leveldb;

import static net.bytebuddy.agent.builder.AgentBuilder.RedefinitionStrategy.RETRANSFORMATION;
import static net.bytebuddy.matcher.ElementMatchers.is;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;
import org.fusesource.leveldbjni.internal.NativeLogger;
import org.fusesource.leveldbjni.internal.NativeLogger.LoggerJNI;
import org.springframework.test.util.ReflectionTestUtils;

/** Observes the actual LoggerJNI.delete call without changing the native implementation. */
final class LevelDbLoggerDeletes implements AutoCloseable {

  private static final ThreadLocal<LevelDbLoggerDeletes> ACTIVE = new ThreadLocal<>();
  private final Map<Long, NativeLogger> live = new HashMap<>();
  private final Set<NativeLogger> freed = Collections.newSetFromMap(new IdentityHashMap<>());
  private final Instrumentation instrumentation = ByteBuddyAgent.install();
  private final ResettableClassFileTransformer transformer;

  LevelDbLoggerDeletes() throws ReflectiveOperationException {
    assertNull("Nested LevelDB Logger observer", ACTIVE.get());
    Class<?> managedLogger = Class.forName(LevelDbFactory.class.getName() + "$ManagedNativeLogger",
        false, LevelDbFactory.class.getClassLoader());
    Method nativeDelete = LoggerJNI.class.getMethod("delete", long.class);
    Method observedDelete = LevelDbLoggerDeletes.class.getDeclaredMethod("delete", long.class);
    boolean[] transformed = {false};
    transformer = new AgentBuilder.Default().disableClassFormatChanges()
        .with(RETRANSFORMATION)
        .with(new AgentBuilder.Listener.Adapter() {
          @Override
          public void onTransformation(TypeDescription type, ClassLoader loader, JavaModule module,
              boolean loaded, DynamicType dynamicType) {
            transformed[0] = true;
          }
        })
        .type(is(managedLogger))
        .transform((builder, type, loader, module, domain) -> builder.visit(
            MemberSubstitution.relaxed().method(is(nativeDelete)).replaceWith(observedDelete)
                .on(named("delete"))))
        .installOn(instrumentation);
    if (!transformed[0]) {
      transformer.reset(instrumentation, RETRANSFORMATION);
      throw new AssertionError("Could not instrument LevelDB Logger deletion");
    }
    ACTIVE.set(this);
  }

  void track(NativeLogger logger) {
    long address = (long) ReflectionTestUtils.getField(logger, "self");
    assertTrue("Expected a live native Logger", address != 0);
    assertFalse(freed.contains(logger));
    assertNull("Duplicate live native Logger address", live.put(address, logger));
  }

  // Only the call in ManagedNativeLogger is rewritten. Calls from this method still enter JNI.
  static void delete(long address) {
    LevelDbLoggerDeletes observer = ACTIVE.get();
    if (observer == null) {
      LoggerJNI.delete(address);
      return;
    }
    NativeLogger logger = observer.live.get(address);
    assertNotNull("Unexpected or repeated native Logger deletion: " + address, logger);
    LoggerJNI.delete(address);
    observer.live.remove(address);
    observer.freed.add(logger);
  }

  void assertReleased(NativeLogger logger) {
    assertTrue("Native Logger deletion did not complete", freed.contains(logger));
  }

  void assertCounts(int created, int released) {
    assertEquals("Native Loggers observed", created, live.size() + freed.size());
    assertEquals("Completed native Logger deletions", released, freed.size());
  }

  @Override
  public void close() {
    ACTIVE.remove();
    assertTrue("Could not remove LevelDB Logger observer",
        transformer.reset(instrumentation, RETRANSFORMATION));
  }
}
