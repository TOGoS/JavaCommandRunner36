# Eventually this tool should be able to build itself.
# This Makefile is here for bootstrapping.

java_source_files = $(shell find src/main/java -name '*.java')

target/JCR36-dev.jar: target/classes src/main/meta/META-INF/MANIFEST.MF
	jar -cmf src/main/meta/META-INF/MANIFEST.MF "$@" -C target/classes .

target/classes: ${java_source_files}
	mkdir -p "$@"
	javac -d "$@" -source 1.6 -target 1.6 ${java_source_files}
	touch "$@"
