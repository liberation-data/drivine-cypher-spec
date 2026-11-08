package org.drivine.schema

/**
 * The JVM's `kotlin.jvm.internal.DefaultConstructorMarker`, which the schema specs' binary-compatibility constructors take,
 * as the constructor Kotlin made for their default arguments before `ignoreCase` took it: so drivine4j's callers compiled
 * against those versions still link. It is the JVM's own class on the JVM (jvmMain), and a class no caller can make
 * elsewhere, where there is no binary of an earlier version to link with.
 */
expect class DefaultConstructorMarker
