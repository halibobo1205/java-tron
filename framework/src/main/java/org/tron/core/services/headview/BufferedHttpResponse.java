package org.tron.core.services.headview;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

final class BufferedHttpResponse extends HttpServletResponseWrapper {

  private final ByteArrayOutputStream body = new ByteArrayOutputStream();
  private final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
  private final List<Cookie> cookies = new ArrayList<>();
  private final long maxBodyBytes;
  private int status = SC_OK;
  private String errorMessage;
  private boolean error;
  private String contentType;
  private String characterEncoding;
  private PrintWriter writer;
  private ServletOutputStream outputStream;
  private boolean overflow;

  BufferedHttpResponse(HttpServletResponse response, long maxBodyBytes) {
    super(response);
    this.maxBodyBytes = maxBodyBytes;
    this.contentType = response.getContentType();
    this.characterEncoding = response.getCharacterEncoding();
  }

  boolean isOverflow() {
    materialize();
    return overflow;
  }

  void materialize() {
    if (writer != null) {
      writer.flush();
    }
  }

  void commitTo(HttpServletResponse target, Map<String, String> extraHeaders)
      throws IOException {
    materialize();
    if (contentType != null) {
      target.setContentType(contentType);
    }
    if (characterEncoding != null) {
      target.setCharacterEncoding(characterEncoding);
    }
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      for (String value : entry.getValue()) {
        target.addHeader(entry.getKey(), value);
      }
    }
    for (Cookie cookie : cookies) {
      target.addCookie(cookie);
    }
    if (error) {
      if (errorMessage == null) {
        target.sendError(status);
      } else {
        target.sendError(status, errorMessage);
      }
      return;
    }
    for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
      target.setHeader(entry.getKey(), entry.getValue());
    }
    target.setStatus(status);
    target.setContentLength(body.size());
    body.writeTo(target.getOutputStream());
  }

  private void append(byte[] bytes, int offset, int length) {
    if (overflow) {
      return;
    }
    if (body.size() + (long) length > maxBodyBytes) {
      overflow = true;
      return;
    }
    body.write(bytes, offset, length);
  }

  @Override
  public ServletOutputStream getOutputStream() {
    if (writer != null) {
      throw new IllegalStateException("getWriter() has already been called");
    }
    if (outputStream == null) {
      outputStream = new ServletOutputStream() {
        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
          throw new UnsupportedOperationException("async write is not supported");
        }

        @Override
        public void write(int b) {
          append(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
          append(bytes, offset, length);
        }
      };
    }
    return outputStream;
  }

  @Override
  public PrintWriter getWriter() {
    if (outputStream != null) {
      throw new IllegalStateException("getOutputStream() has already been called");
    }
    if (writer == null) {
      Charset charset = characterEncoding == null
          ? StandardCharsets.UTF_8 : Charset.forName(characterEncoding);
      ServletOutputStream sink = new ServletOutputStream() {
        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
          throw new UnsupportedOperationException("async write is not supported");
        }

        @Override
        public void write(int b) {
          append(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
          append(bytes, offset, length);
        }
      };
      writer = new PrintWriter(new OutputStreamWriter(sink, charset));
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
    status = sc;
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
  public void sendRedirect(String location) {
    status = SC_FOUND;
    setHeader("Location", location);
  }

  @Override
  public void setHeader(String name, String value) {
    List<String> values = new ArrayList<>();
    values.add(value);
    headers.put(name, values);
  }

  @Override
  public void addHeader(String name, String value) {
    headers.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
  }

  @Override
  public void setIntHeader(String name, int value) {
    setHeader(name, Integer.toString(value));
  }

  @Override
  public void addIntHeader(String name, int value) {
    addHeader(name, Integer.toString(value));
  }

  @Override
  public void setDateHeader(String name, long date) {
    setHeader(name, Long.toString(date));
  }

  @Override
  public void addDateHeader(String name, long date) {
    addHeader(name, Long.toString(date));
  }

  @Override
  public boolean containsHeader(String name) {
    return headers.containsKey(name);
  }

  @Override
  public String getHeader(String name) {
    List<String> values = headers.get(name);
    return values == null || values.isEmpty() ? null : values.get(0);
  }

  @Override
  public Collection<String> getHeaders(String name) {
    List<String> values = headers.get(name);
    return values == null ? Collections.emptyList() : Collections.unmodifiableList(values);
  }

  @Override
  public Collection<String> getHeaderNames() {
    return Collections.unmodifiableSet(headers.keySet());
  }

  @Override
  public void addCookie(Cookie cookie) {
    cookies.add(cookie);
  }

  @Override
  public void setContentType(String type) {
    contentType = type;
    if (type != null) {
      int index = type.toLowerCase(Locale.ROOT).indexOf("charset=");
      if (index >= 0) {
        characterEncoding = type.substring(index + "charset=".length()).trim();
      }
    }
  }

  @Override
  public String getContentType() {
    return contentType;
  }

  @Override
  public void setCharacterEncoding(String charset) {
    characterEncoding = charset;
  }

  @Override
  public String getCharacterEncoding() {
    return characterEncoding == null ? StandardCharsets.UTF_8.name() : characterEncoding;
  }

  @Override
  public void setContentLength(int len) {
    // the committed length is computed from the buffered body
  }

  @Override
  public void setContentLengthLong(long len) {
    // the committed length is computed from the buffered body
  }

  @Override
  public void setBufferSize(int size) {
    // buffering is unbounded up to maxBodyBytes
  }

  @Override
  public void flushBuffer() {
    materialize();
  }

  @Override
  public boolean isCommitted() {
    return false;
  }

  @Override
  public void reset() {
    resetBuffer();
    headers.clear();
    cookies.clear();
    status = SC_OK;
    error = false;
    errorMessage = null;
  }

  @Override
  public void resetBuffer() {
    materialize();
    body.reset();
    overflow = false;
  }
}
