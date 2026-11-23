package org.tron.core.services.headview;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;

final class CachedBodyRequest extends HttpServletRequestWrapper {

  private final byte[] body;

  CachedBodyRequest(HttpServletRequest request, byte[] body) {
    super(request);
    this.body = body;
  }

  @Override
  public ServletInputStream getInputStream() {
    ByteArrayInputStream in = new ByteArrayInputStream(body);
    return new ServletInputStream() {
      @Override
      public boolean isFinished() {
        return in.available() == 0;
      }

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setReadListener(ReadListener readListener) {
        throw new UnsupportedOperationException("async read is not supported");
      }

      @Override
      public int read() {
        return in.read();
      }

      @Override
      public int read(byte[] buffer, int offset, int length) {
        return in.read(buffer, offset, length);
      }
    };
  }

  @Override
  public BufferedReader getReader() {
    return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset()));
  }

  private Charset charset() {
    String encoding = getCharacterEncoding();
    if (encoding != null) {
      return Charset.forName(encoding);
    }
    String contentType = getContentType();
    boolean json = contentType != null
        && contentType.toLowerCase(Locale.ROOT).startsWith("application/json");
    return json ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1;
  }
}
