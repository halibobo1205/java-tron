package org.tron.core.services.http.regtest.headviewsolidity;

import org.springframework.stereotype.Component;
import org.tron.core.services.http.HttpApi;
import org.tron.core.services.http.HttpApi.Access;
import org.tron.core.services.http.HttpApi.Surface;
import org.tron.core.services.http.servlets.RateLimiterServlet;

/** Invalid: a committed-head endpoint that is not on the FULL surface. */
@Component
@HttpApi(value = "headviewsolidity", access = Access.READ, surfaces = {Surface.SOLIDITY},
    committedHead = true)
public class HeadViewSolidityServlet extends RateLimiterServlet {
}
