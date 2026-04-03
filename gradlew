#!/bin/sh
PRG="$0"
while [ -h "$PRG" ] ; do
    ls=`ls -ld "$PRG"`
    link=`expr "$ls" : '.*-> \(.*\)$'`
    if expr "$link" : '/.*' > /dev/null; then PRG="$link"
    else PRG=`dirname "$PRG"`"/$link"
    fi
done
APP_HOME="`dirname \"$PRG\"`"
APP_HOME="`cd \"$APP_HOME\" && pwd`"

if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

exec "$JAVACMD" \
  -Xmx512m \
  -Dorg.gradle.appname="Gradle" \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain \
  "$@"
