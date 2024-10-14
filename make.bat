@echo off

if not defined TS34P19_JAR echo Please set TS34P19_JAR>&2 & exit /B 1

set JAVAC_SOURCE_VERSION=1.7
set JAVAC_TARGET_VERSION=1.7

java -jar "%TS34P19_JAR%" ts34p19:compile-jar ^
	--include-sources ^
	--resources=src/main/meta ^
	--java-sources=src/main/java ^
	-o target\JCR36-dev.jar
