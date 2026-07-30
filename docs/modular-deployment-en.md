# How to deploy java-tron after modularization

After modularization, the recommended way to launch java-tron is via the shell script generated in `bin/`. The classic `java -jar FullNode.jar` command is still fully supported as an alternative.

> **Supported platforms**: Linux and macOS. Windows is not supported.

## Download

```
git clone git@github.com:tronprotocol/java-tron.git
```

## Compile

Change to project directory and run:
```
./gradlew build
```
java-tron-1.0.0.zip will be generated in java-tron/build/distributions after compilation.

## Unzip

Unzip java-tron-1.0.0.zip
```
cd java-tron/build/distributions
unzip -o java-tron-1.0.0.zip
```
After unzip, two directories will be generated in java-tron: `bin` and `lib`, shell scripts are located in `bin`, jars are located in `lib`.

## Startup

Use the shell script to start java-tron (Linux / macOS):
```
# default
java-tron-1.0.0/bin/FullNode

# using config file, there are some demo configs in java-tron/framework/build/resources
java-tron-1.0.0/bin/FullNode -c config.conf

# when startup with SR mode，add parameter: -w
java-tron-1.0.0/bin/FullNode -c config.conf -w
```

## JVM configuration

java-tron requires JDK 8 or newer on x86_64 and JDK 17 or newer on ARM64. The
packaged defaults use the JDK 17+ JVM options when built with JDK 17 or newer;
otherwise they use the CMS options.

JVM options can also be specified in `bin/java-tron.vmoptions`:
```
# demo for JDK versions below 17
-Xms2g
-Xmx9g
-XX:+PrintGCDetails
-Xloggc:./gc.log
-XX:+PrintGCDateStamps
-XX:ReservedCodeCacheSize=256m
```