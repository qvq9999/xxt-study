# 学习通接口解析依赖 Jsoup 的 DOM 能力，保留默认规则即可。
# OkHttp 在 release 下需要这对规则，否则部分反射调用会缺字段。
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.jsoup.**

# kotlinx.serialization 的序列化器由编译器插件生成，保留 @Serializable 类的伴生对象
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
