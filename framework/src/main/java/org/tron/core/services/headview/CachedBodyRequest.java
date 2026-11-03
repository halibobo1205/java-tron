package org.tron.core.services.headview;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.UrlEncoded;

final class CachedBodyRequest extends HttpServletRequestWrapper {

  private static final String FORM = "application/x-www-form-urlencoded";

  private final byte[] body;
  private Map<String, String[]> parameters;

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

  @Override
  public String getParameter(String name) {
    String[] values = getParameterMap().get(name);
    return values == null ? null : values[0];
  }

  @Override
  public Map<String, String[]> getParameterMap() {
    if (parameters == null) {
      parameters = mergeParameters();
    }
    return parameters;
  }

  @Override
  public Enumeration<String> getParameterNames() {
    return Collections.enumeration(getParameterMap().keySet());
  }

  @Override
  public String[] getParameterValues(String name) {
    String[] values = getParameterMap().get(name);
    return values == null ? null : values.clone();
  }

  private Map<String, String[]> mergeParameters() {
    Map<String, List<String>> merged = new LinkedHashMap<>();
    super.getParameterMap().forEach((name, values) ->
        merged.computeIfAbsent(name, k -> new ArrayList<>()).addAll(Arrays.asList(values)));
    if (isForm()) {
      decodeForm(merged);
    }
    Map<String, String[]> result = new LinkedHashMap<>();
    merged.forEach((name, values) -> result.put(name, values.toArray(new String[0])));
    return Collections.unmodifiableMap(result);
  }

  private void decodeForm(Map<String, List<String>> merged) {
    MultiMap<String> form = new MultiMap<>();
    try {
      UrlEncoded.decodeTo(new ByteArrayInputStream(body), form, getCharacterEncoding(),
          ContextHandler.DEFAULT_MAX_FORM_CONTENT_SIZE, ContextHandler.DEFAULT_MAX_FORM_KEYS);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (IllegalStateException | IllegalArgumentException e) {
      throw new BadMessageException("Unable to parse form content", e);
    }
    form.forEach((name, values) ->
        merged.computeIfAbsent(name, k -> new ArrayList<>()).addAll(values));
  }

  private boolean isForm() {
    String contentType = getContentType();
    return "POST".equals(getMethod()) && contentType != null
        && contentType.toLowerCase(Locale.ROOT).startsWith(FORM);
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
