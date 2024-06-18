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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.tron.common.application;

import io.grpc.Server;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

@Slf4j(topic = "rpc")
public abstract class RpcService extends AbstractService {

  protected Server apiServer;

  @Override
  public void innerStart() throws Exception {
    if (apiServer != null) {
      apiServer.start();
    }
  }

  @Override
  public void innerStop() throws Exception {
    if (apiServer != null) {
      apiServer.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

}
