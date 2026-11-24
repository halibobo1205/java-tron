package org.tron.core.services.http.regtest.headviewplain;

import javax.servlet.http.HttpServlet;
import org.springframework.stereotype.Component;
import org.tron.core.services.http.HttpApi;
import org.tron.core.services.http.HttpApi.Access;
import org.tron.core.services.http.HttpApi.Surface;

/** Invalid: a committed-head endpoint that does not extend RateLimiterServlet. */
@Component
@HttpApi(value = "headviewplain", access = Access.READ, surfaces = {Surface.FULL},
    committedHead = true)
public class HeadViewPlainServlet extends HttpServlet {
}
