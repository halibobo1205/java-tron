package org.tron.core.services.headview;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
  public BufferedReader getReader() throws IOException {
    String encoding = getCharacterEncoding();
    return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body),
        encoding == null ? StandardCharsets.ISO_8859_1.name() : encoding));
  }
}
