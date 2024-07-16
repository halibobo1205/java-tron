package org.tron.common.arch;

public class Arch {

  private final StringBuilder info = new StringBuilder();

  public static Arch builder() {
    return new Arch();
  }

  public String build() {
    return info.toString();
  }

  public Arch withOsName() {
    info.append("os.name").append(": ").append(getOsName()).append("\n");
    return this;
  }

  public Arch withOsArch() {
    info.append("os.arch").append(": ").append(getOsArch()).append("\n");
    return this;
  }

  public Arch withBitModel() {
    info.append("bit.model").append(": ").append(getBitModel()).append("\n");
    return this;
  }

  public Arch withJavaVersion() {
    info.append("java.version").append(": ").append(javaVersion()).append("\n");
    return this;
  }

  public Arch withJavaSpecificationVersion() {
    info.append("java.specification.version").append(": ").append(javaSpecificationVersion())
        .append("\n");
    return this;
  }

  public Arch withJavaVendor() {
    info.append("java.vendor").append(": ").append(javaVendor()).append("\n");
    return this;
  }

  public static Arch withAll() {
    return Arch.builder()
        .withOsName()
        .withOsArch()
        .withBitModel()
        .withJavaVersion()
        .withJavaSpecificationVersion()
        .withJavaVendor();
  }

  public static String getOsName() {
    return System.getProperty("os.name").toLowerCase().trim();

  }
  public static String getOsArch() {
    return System.getProperty("os.arch").toLowerCase().trim();
  }

  public static int getBitModel() {
    String prop = System.getProperty("sun.arch.data.model");
    if (prop == null) {
      prop = System.getProperty("com.ibm.vm.bitmode");
    }
    if (prop != null) {
      return Integer.parseInt(prop);
    }
    // GraalVM support, see https://github.com/fusesource/jansi/issues/162
    String arch = System.getProperty("os.arch");
    if (arch.endsWith("64") && "Substrate VM".equals(System.getProperty("java.vm.name"))) {
      return 64;
    }
    return -1; // we don't know...
  }

  public static String javaVersion() {
    return System.getProperty("java.version").toLowerCase().trim();
  }

  public static String javaSpecificationVersion() {
    return System.getProperty("java.specification.version").toLowerCase().trim();
  }

  public static String javaVendor() {
    return System.getProperty("java.vendor").toLowerCase().trim();
  }

  public static boolean isArm64() {
    String osArch = getOsArch();
    return osArch.contains("arm64") || osArch.contains("aarch64");
  }

  @Override
  public String toString() {
    return info.toString();
  }
}
