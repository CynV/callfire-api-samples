#!/bin/sh

if [ $# -eq 0 ]; then
	echo "Usage: $0 <API Sample Name>"
	exit 1
fi

EXAMPLE_CP=lib/commons-lang-2.6.jar:lib/callfire-api.jar:lib/commons-configuration-1.8.jar:lib/commons-logging-1.1.1.jar:.
mkdir -p build && javac -classpath $EXAMPLE_CP -d build $1.java && java -classpath $EXAMPLE_CP:build $1
