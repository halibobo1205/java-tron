package org.tron.core.services.headview;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import org.tron.core.db2.view.HeadLease;
import org.tron.core.db2.view.HeadViewException;

final class BufferedHttpResponse extends HttpServletResponseWrapper {

  private final ByteArrayOutputStream body = new ByteArrayOutputStream();
  private final HeadLease lease;
  private final long maxBodyBytes;
  private final Charset charset;
  private PrintWriter writer;
  private boolean overflow;
  private int status = SC_OK;
  private boolean error;
  private String errorMessage;

  BufferedHttpResponse(HttpServletResponse response, HeadLease lease, long maxBodyBytes) {
    super(response);
    this.lease = lease;
    this.maxBodyBytes = maxBodyBytes;
    String encoding = response.getCharacterEncoding();
    this.charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
  }

  boolean isOverflow() {
    if (writer != null) {
      writer.flush();
    }
    return overflow;
  }

  void commitTo(HttpServletResponse target, Map<String, String> extraHeaders)
      throws IOException {
    if (error) {
      target.sendError(status, errorMessage);
      return;
    }
    extraHeaders.forEach(target::setHeader);
    target.setStatus(status);
    target.setContentLength(body.size());
    body.writeTo(target.getOutputStream());
  }

  @Override
  public PrintWriter getWriter() {
    if (writer == null) {
      writer = new PrintWriter(new OutputStreamWriter(new BoundedSink(), charset));
    }
    return writer;
  }

  @Override
  public void setStatus(int sc) {
    status = sc;
  }

  @Override
  @SuppressWarnings("deprecation")
  public void setStatus(int sc, String message) {
    throw unsupported("setStatus");
  }

  @Override
  public int getStatus() {
    return status;
  }

  @Override
  public void sendError(int sc) {
    sendError(sc, null);
  }

  @Override
  public void sendError(int sc, String message) {
    status = sc;
    errorMessage = message;
    error = true;
  }

  @Override
  public ServletOutputStream getOutputStream() {
    throw unsupported("getOutputStream");
  }

  @Override
  public void sendRedirect(String location) {
    throw unsupported("sendRedirect");
  }

  @Override
  public void setHeader(String name, String value) {
    throw unsupported("setHeader");
  }

  @Override
  public void addHeader(String name, String value) {
    throw unsupported("addHeader");
  }

  @Override
  public void setIntHeader(String name, int value) {
    throw unsupported("setIntHeader");
  }

  @Override
  public void addIntHeader(String name, int value) {
    throw unsupported("addIntHeader");
  }

  @Override
  public void setDateHeader(String name, long date) {
    throw unsupported("setDateHeader");
  }

  @Override
  public void addDateHeader(String name, long date) {
    throw unsupported("addDateHeader");
  }

  @Override
  public void addCookie(Cookie cookie) {
    throw unsupported("addCookie");
  }

  @Override
  public void setContentType(String type) {
    throw unsupported("setContentType");
  }

  @Override
  public void setCharacterEncoding(String encoding) {
    throw unsupported("setCharacterEncoding");
  }

  @Override
  public void setLocale(Locale locale) {
    throw unsupported("setLocale");
  }

  @Override
  public void setContentLength(int len) {
    throw unsupported("setContentLength");
  }

  @Override
  public void setContentLengthLong(long len) {
    throw unsupported("setContentLengthLong");
  }

  @Override
  public void setBufferSize(int size) {
    throw unsupported("setBufferSize");
  }

  @Override
  public void flushBuffer() {
    throw unsupported("flushBuffer");
  }

  @Override
  public void reset() {
    throw unsupported("reset");
  }

  @Override
  public void resetBuffer() {
    throw unsupported("resetBuffer");
  }

  @Override
  public ServletResponse getResponse() {
    throw unsupported("getResponse");
  }

  @Override
  public void setResponse(ServletResponse response) {
    throw unsupported("setResponse");
  }

  private HeadViewException unsupported(String method) {
    return lease.unsupported("response." + method);
  }

  private final class BoundedSink extends OutputStream {

    @Override
    public void write(int b) {
      write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) {
      if (overflow) {
        return;
      }
      if (body.size() + (long) length > maxBodyBytes) {
        overflow = true;
        return;
      }
      body.write(bytes, offset, length);
    }
  }
}
