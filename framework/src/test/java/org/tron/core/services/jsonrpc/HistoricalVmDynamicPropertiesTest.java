package org.tron.core.services.jsonrpc;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.tron.core.services.jsonrpc.JsonRpcApiUtil.parseEnergyFee;

import org.junit.Test;
import org.tron.core.store.VmDynamicProperties;

/**
 * The historical {@code eth_call} config view returns the energy price in force at the target block
 * (reconstructed from the live, time-keyed {@code EnergyPriceHistory}) while every other flag falls
 * back to the latest store.
 */
public class HistoricalVmDynamicPropertiesTest {

  @Test
  public void getEnergyFeeReturnsHistoricalValueAndDelegatesTheRest() {
    VmDynamicProperties latest = mock(VmDynamicProperties.class);
    when(latest.getEnergyFee()).thenReturn(420L);            // the current (latest) price
    when(latest.getAllowTvmLondon()).thenReturn(1L);
    when(latest.getMaxFeeLimit()).thenReturn(15_000_000_000L);

    HistoricalVmDynamicProperties view = new HistoricalVmDynamicProperties(latest, 140L);

    assertEquals(140L, view.getEnergyFee());                 // historical, NOT the latest 420
    assertEquals(1L, view.getAllowTvmLondon());              // delegated to latest
    assertEquals(15_000_000_000L, view.getMaxFeeLimit());    // delegated to latest
  }

  @Test
  public void parseEnergyFeePicksThePriceInForceAtTheBlockTime() {
    // "<activationTime>:<price>" accumulated from genesis; a block at t=3000 was priced at the 2000
    // entry (140), proving the historical call uses the then-current energy price, not the newest.
    String history = "0:100,2000:140,5000:210";
    assertEquals(100L, parseEnergyFee(1L, history));
    assertEquals(140L, parseEnergyFee(3000L, history));
    assertEquals(210L, parseEnergyFee(9000L, history));
  }

  @Test
  public void resolveHistoricalEnergyFeeFallsBackToHistoricalDefault() {
    // Timestamp 0 is before the first "0:100" strict activation boundary in parseEnergyFee. The
    // historical path must still use the genesis default, not latestStore.getEnergyFee().
    assertEquals(100L,
        HistoricalVmDynamicProperties.resolveHistoricalEnergyFee(0L, "0:100,2000:140"));
  }
}
