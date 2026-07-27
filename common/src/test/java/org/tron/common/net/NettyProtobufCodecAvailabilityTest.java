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

package org.tron.common.net;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;
import org.tron.p2p.connection.Channel;

/**
 * Netty 4.2 moved the protobuf codecs out of {@code netty-codec} into the separate
 * {@code netty-codec-protobuf} artifact. The class names did not change, so for a
 * pre-compiled jar a missing dependency is invisible at compile time: libp2p is built
 * against Netty 4.1.x and instantiates {@code ProtobufVarint32LengthFieldPrepender} /
 * {@code ProtobufVarint32FrameDecoder} inside its P2P pipelines
 * ({@code org.tron.p2p.connection.Channel} for TCP, {@code DiscoverServer} for UDP).
 * Dropping the artifact would only surface as a runtime {@link NoClassDefFoundError}
 * once peers connect.
 *
 * <p>The codec classes are referenced by name rather than imported on purpose, so that
 * removing the dependency reproduces libp2p's real failure mode - a runtime resolution
 * error - instead of failing this test at compile time.
 */
public class NettyProtobufCodecAvailabilityTest {

  private static final String PREPENDER =
      "io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender";
  private static final String FRAME_DECODER =
      "io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder";

  @Test
  public void protobufVarint32CodecsAreOnTheRuntimeClasspath() throws Exception {
    for (String name : new String[] {PREPENDER, FRAME_DECODER}) {
      Class<?> type = Class.forName(name);
      assertTrue(name + " must be a ChannelHandler",
          ChannelHandler.class.isAssignableFrom(type));
      assertNotNull(name + " must be instantiable",
          type.getDeclaredConstructor().newInstance());
    }
  }

  /**
   * libp2p's TCP pipeline setup instantiates the protobuf codecs internally, so running it
   * against a real pipeline fails with NoClassDefFoundError when netty-codec-protobuf is absent.
   */
  @Test
  public void libP2pChannelInitBuildsProtobufPipeline() throws Exception {
    EmbeddedChannel embeddedChannel = new EmbeddedChannel();
    try {
      ChannelPipeline pipeline = embeddedChannel.pipeline();
      new Channel().init(pipeline, "test-node-id", false);

      @SuppressWarnings("unchecked")
      Class<? extends ChannelHandler> prependerType =
          (Class<? extends ChannelHandler>) Class.forName(PREPENDER);
      assertNotNull("libp2p pipeline must carry the protobuf length prepender",
          pipeline.get(prependerType));
    } finally {
      embeddedChannel.finishAndReleaseAll();
    }
  }
}
