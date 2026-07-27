/*
 * java-tron is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * java-tron is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with java-tron.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.tron.program;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class FullNodeTest {

  private String previousAllocatorType;

  @Before
  public void setUp() {
    previousAllocatorType = System.getProperty(FullNode.NETTY_ALLOCATOR_TYPE_PROPERTY);
  }

  @After
  public void tearDown() {
    if (previousAllocatorType == null) {
      System.clearProperty(FullNode.NETTY_ALLOCATOR_TYPE_PROPERTY);
    } else {
      System.setProperty(FullNode.NETTY_ALLOCATOR_TYPE_PROPERTY, previousAllocatorType);
    }
  }

  @Test
  public void shouldUsePooledNettyAllocatorByDefault() {
    System.clearProperty(FullNode.NETTY_ALLOCATOR_TYPE_PROPERTY);

    FullNode.configureNettyAllocator();

    assertEquals("pooled", System.getProperty(FullNode.NETTY_ALLOCATOR_TYPE_PROPERTY));
  }

  @Test
  public void shouldPreserveExplicitNettyAllocator() {
    System.setProperty(FullNode.NETTY_ALLOCATOR_TYPE_PROPERTY, "adaptive");

    FullNode.configureNettyAllocator();

    assertEquals("adaptive", System.getProperty(FullNode.NETTY_ALLOCATOR_TYPE_PROPERTY));
  }
}
