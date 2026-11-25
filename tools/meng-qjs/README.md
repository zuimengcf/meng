# meng-qjs 0.18.0（QuickJS-ng 沙箱版）

基于 quickjs-ng v0.18.0 官方 amalgamation，**不含 quickjs-libc**：脚本没有 std/os、文件、网络、进程、定时器，也没有任何宿主函数。

## 直接用
1. `meng-qjs-0.18.0.aar` 放到 `app/libs/`
2. `app/build.gradle.kts`：`implementation(files("libs/meng-qjs-0.18.0.aar"))`
3. 混淆规则已内置（proguard.txt），无需另加

```kotlin
QjsEngine().use { qjs ->   // 默认：堆 128MB / 栈 256KB / 脚本 64MB / 单次输入 2MB / 输出 8MB
    qjs.load(scriptSource, "my-plugin/main.js", 5000)   // 带大表的脚本加载给足时间
    val out = qjs.call("transform", text, """{"targetPackage":"com.tencent.mm"}""", 50)
}
```
插件脚本只需定义全局函数：`function transform(text, ctx) { return text }`，必须返回字符串。

异常统一为 `QjsException`，`kind`：SCRIPT / TIMEOUT / INTERRUPTED / OOM / BAD_RESULT / INTERNAL / TOO_LARGE / CLOSED。任何异常都应上屏原文。
`interrupt()` 可从任意线程调用；调用可跨线程（内部串行化）。

## 限制
- 构造：`QjsEngine(mem, stack, maxSource, maxInput, maxOutput)`，任一项传 0 = 不限（栈不建议改）
- 脚本大小与单次输入分开算，拼音表等大数据放脚本里不受输入上限影响
- 实测 6.4MB 表（20 万条 JSON + 10 万条对象字面量）：堆约 70MB，宿主加载 0.66s，查表调用约 1ms；手机预计慢 2~4 倍
- 大表写成 `const T = JSON.parse('…')` 比对象字面量省内存、解析快；引擎加载一次常驻复用，不要每次粘贴都 load

## 安全建议
- 放进 `android:isolatedProcess="true"` 的独立 Service 里跑，Binder 只传字符串
- 每个插件一个 QjsEngine；连续 3 次 TIMEOUT/OOM 即停用
- OOM 在 JS 里可被 try/catch，但内存上限仍然生效

## 产物
- ABI：arm64-v8a 686KB、armeabi-v7a 515KB、x86_64 731KB、x86 711KB（已 strip）
- minSdk 24，16KB 页对齐（Android 15+），只依赖 libc/libm，只导出 7 个 JNI 符号

## 自己重编
- `source/` 是标准 Android library 模块（CMake + NDK r27c），`assembleRelease` 即可
- 或不用 Gradle：`pip install ziglang`，装好 JDK 8+ 和 NDK r27c，然后 `NDK=/path/android-ndk-r27c source/build-aar.sh`，产物在 `source/out/`（本 AAR 就是这样编的；只要 .so 用 `build-so.sh`）
- 升级 ng：替换 `source/src/main/cpp/quickjs/` 下的 quickjs-amalgam.c / quickjs.h（从 release 的 quickjs-amalgam.zip 取）。注意 0.18 的 `JSInterruptHandler` 参数是 `JSContext*`

## 许可
QuickJS-ng：MIT（见 LICENSE.quickjs-ng）。JNI 封装：MIT。
