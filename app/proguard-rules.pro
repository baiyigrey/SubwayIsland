# Xposed 入口不能混淆/裁剪名称
-keep class com.subwayisland.XposedInit { *; }
-keep class com.subwayisland.systemui.** { *; }
-keep class com.subwayisland.map.** { *; }
-keepattributes *Annotation*
-dontwarn de.robv.android.xposed.**
