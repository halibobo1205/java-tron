package org.tron.core.services.http.regtest.headviewbuild;

import org.springframework.stereotype.Component;
import org.tron.core.services.http.HttpApi;
import org.tron.core.services.http.HttpApi.Access;
import org.tron.core.services.http.HttpApi.Surface;
import org.tron.core.services.http.servlets.RateLimiterServlet;

/** Invalid: a BUILD endpoint that opts into the committed-head view. */
@Component
@HttpApi(value = "headviewbuild", access = Access.BUILD, surfaces = {Surface.FULL},
    committedHead = true)
public class HeadViewBuildServlet extends RateLimiterServlet {
}
