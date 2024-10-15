package org.tron.core.services.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.tron.core.services.http.FullNodeHttpApiService;

/**
 * Annotation for {@link FullNodeHttpApiService} servlet.
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface FullServlet {

  /**
   *  servlet pathSpec.
   */
  String[] value();
}
