# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ✅ 为云卓 SDK 提供保护，防止其自身代码被错误地移除
-keep class com.skydroid.** { *; }

# ✅✅ 命令编译器忽略关于以下缺失的“子依赖”的所有警告和错误
# 这是解决 Missing class 问题的最终方案
-dontwarn com.fishsemi.**
-dontwarn com.skydroid.fpvplayer.**
-dontwarn org.apache.commons.net.**
