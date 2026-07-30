# 模块化后的 java-tron 部署方式

模块化后，推荐使用 `bin/` 目录下生成的脚本启动 java-tron。原有的 `java -jar FullNode.jar` 方式仍完全支持，作为备选方式使用。

> **支持平台**：Linux 和 macOS。不支持 Windows。

## 下载

```
git clone git@github.com:tronprotocol/java-tron.git
```

## 编译

进入项目目录执行：
```
./gradlew build
```
编译成功后在 java-tron/build/distributions 目录下生成 zip 压缩包：java-tron-1.0.0.zip

## 解压

解压 java-tron-1.0.0.zip
```
cd java-tron/build/distributions
unzip -o java-tron-1.0.0.zip
```
解压后的 java-tron 目录中有 bin 和 lib 两个文件夹，bin 目录中存放的是可执行脚本，lib 下存放程序依赖的 jar 包。

## 启动

使用脚本启动 java-tron（Linux / macOS）：
```
# 默认配置文件启动
java-tron-1.0.0/bin/FullNode
# 自定义配置文件启动, java-tron/framework/build/resources 目录下有默认的配置文件提供选择
java-tron-1.0.0/bin/FullNode -c config.conf
# SR 模式启动，需要加上：-w
java-tron-1.0.0/bin/FullNode -c config.conf -w
```

## jvm参数配置

java-tron 在 x86_64 架构上要求 JDK 8 及以上版本，在 ARM64 架构上要求 JDK 17
及以上版本。使用 JDK 17 及以上版本构建时，发布包默认使用 JDK 17+ 的 JVM 参数；
低于 JDK 17 时使用 CMS 参数。

java-tron 支持在 bin 目录下的 java-tron.vmoptions 文件中配置 JVM 参数。
```
# JDK 17 以下版本示例
-Xms2g
-Xmx9g
-XX:+PrintGCDetails
-Xloggc:./gc.log
-XX:+PrintGCDateStamps
-XX:ReservedCodeCacheSize=256m
```