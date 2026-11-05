# JNI 方法名必须原样保留：R8 会重命名类和方法名，而 native 侧符号是
# Java_<包名>_<类名>_<方法名>，改名后 System.loadLibrary 成功但调用即 UnsatisfiedLinkError。
-keepclasseswithmembernames class * {
    native <methods>;
}

# Compose 运行时依赖反射访问的极少，但 Activity/Service 由框架实例化，保留入口类名。
-keep class cn.luoyi0914.ofmgateway.MainActivity { *; }
-keep class cn.luoyi0914.ofmgateway.ConsoleActivity { *; }
-keep class cn.luoyi0914.ofmgateway.GatewayService { *; }
-keep class cn.luoyi0914.ofmgateway.BootReceiver { *; }

# 数据类状态通过 StateFlow 传递，避免混淆后字段名与日志/诊断字段对不上。
-keepclassmembers class cn.luoyi0914.ofmgateway.GatewayUiState { *; }

# org.json 由框架提供，无需保留规则；Compose 自带 consumer rules。
-dontwarn org.json.**
