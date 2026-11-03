package org.tron.core.services.http.regtest.headviewvalid;

import org.springframework.stereotype.Component;
import org.tron.core.services.http.HttpApi;
import org.tron.core.services.http.HttpApi.Access;
import org.tron.core.services.http.HttpApi.Surface;
import org.tron.core.services.http.servlets.RateLimiterServlet;

/** Valid committed-head READ fixture. */
@Component
@HttpApi(value = "headviewread", access = Access.READ, surfaces = {Surface.FULL, Surface.SOLIDITY},
    committedHead = true)
public class HeadViewReadServlet extends RateLimiterServlet {
}
