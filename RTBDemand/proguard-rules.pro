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

-keep class com.rtb.rtbdemand.** {*; }
-keep class com.appharbr.** {*;}
-keep interface com.appharbr.** {*;}
-keepclassmembers class com.appharbr.** { public *; }
-keep class p.haeg.w.** {*;}
-keep interface p.haeg.w.** {*;}
-keepclassmembers class cp.haeg.w.** { public *; }
-keep class com.amazon.** { *; }
-keep public class com.google.android.gms.ads.** {
    public *;
}
-dontwarn java.lang.invoke.StringConcatFactory