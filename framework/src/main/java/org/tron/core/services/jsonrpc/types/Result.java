package org.tron.core.services.jsonrpc.types;

import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.tron.common.utils.ByteArray;

public abstract class Result {

  public static String toHex(long l) {
    return l <= 0 ? null : ByteArray.toJsonHex(l);
  }

  public static String toHex(int i) {
    return ByteArray.toJsonHex(i);
  }

  public static String toHex(boolean bool) {
    return bool ? toHex(1) : null;
  }

  public static String toHex(ByteString bytes) {
    return bytes.isEmpty() ? null : ByteArray.toJsonHex(bytes.toByteArray());
  }

  public static String toHex(String s) {
    return s == null || s.isEmpty() ? null :
        ByteArray.toJsonHex(s.getBytes(StandardCharsets.UTF_8));
  }

  // for assetV2
  public static List<Token10Result> toHex(Map<String, Long> input) {
    return input.entrySet().stream().filter(e -> e.getValue() > 0).map(Token10Result::new)
        .sorted(Comparator.comparing(Token10Result::getKey))
        .collect(Collectors.toList());
  }
}
