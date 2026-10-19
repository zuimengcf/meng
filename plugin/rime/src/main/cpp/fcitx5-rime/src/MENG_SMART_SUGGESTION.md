# 梦输入法：原生智能联想与纠错

0.1.4 接口版已将固定标签、数量、来源和纠错位置改为外部可配置，并支持限时外部候选表。下文为原生基线，最多五个等固定描述以工程根目录 `SMART_INPUT_API.md` 的 v1 协议为准。默认标签为空，默认数量仍为五个。

## 直接作用于输入法层

候选由 `fcitx5-rime` 的 `RimeState` 和 `RimeCandidateList` 生成，选词通过输入上下文 `commitString` 上屏。不是聊天窗口的文本润色，也不依赖剪贴板或无障碍服务。当前工程的实现面向 Rime 中文输入；不是对所有第三方输入引擎的通用纠错。

上屏后且没有正在编辑的拼音时，使用最近上屏的最后两个汉字查询下一词，最多展示五个联想。输入拼音时，原生候选之后追加最多五个纠错候选；即使 Rime 没有给出候选，也可展示纠错。已经部分选定的长句不追加整串替换纠错，以免丢失已转换内容。不会在用户未选词时擅自改写文本。

纠错依次考虑精确习惯码、单处模糊音、邻键/相邻键序颠倒/漏键/多键，并按各组频率排序、去重。两字母及以下只接受精确码，避免简拼误改。模糊音支持平翘舌、前后鼻音及 n/l、f/h；总开关及子开关均在 native 层检查。

## 数据与隐私

Android 数据层在独立串行任务队列中学习、清空和导出，Room 挂起期间也不会让后续记录穿插。每次学习后更新词库，使用同目录 POSIX 原子重命名，兼容 Android API 23 起的工程配置。

- `suggestion.dict`：`前词尾两字<TAB>下一完整词<TAB>频率`，首列保留中文。
- `correction.dict`：`拼音码<TAB>完整上屏词<TAB>频率`。
- `smart-input.conf`：`suggestion/correction/neighbor/fuzzy/habit=0或1`，缺失时 native 保守关闭。

上述文件位于 `getExternalFilesDir(null)/data/rime`，与 native `StandardPathsType::PkgData/rime` 对应。使用随包的小型离线常用词表进行冷启动，再合并个人学习数据；不联网、不调用大模型。关闭“智能统计”停止新增学习，但仍可使用基础词表及已有数据。“清空统计”删除个人数据并重建仅含基础词表的导出文件。

密码、数字密码和声明不允许个性化学习的输入框不写入学习统计；native 在密码、敏感、禁用建议、网址/邮箱输入环境或英文模式下不展示本扩展候选。编辑器切换、输入法切换、外部光标移动等会重置学习上下文；上下文两分钟超时，标点和空白断链。

## 构建与验证

工程仅打包 arm64-v8a。`tools/build-smart-rime.sh` 负责用随源码提供的静态 Rime 及依赖重建插件；不能简单链接并覆盖旧的同名 `librime.so`，否则会出现同名自依赖。Gradle 构建接入见 `app/build.gradle.kts`。所需 Android SDK、NDK 28（或 27）及 CMake 需在本机配置。

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest --max-workers=2
```

原生算法回归测试：

```bash
g++ -std=c++20 -Wall -Wextra -Werror -fsanitize=address,undefined \
  tests/smart_input/test_rimesuggestions.cpp -o /tmp/meng-smart-test
/tmp/meng-smart-test
```

Android 纯文本学习规则测试位于 `app/src/test/java/org/fcitx/fcitx5/android/data/stats/SmartInputTextTest.kt`。编译及单元测试不等同于真机输入测试；安装后还需验证宿主应用兼容性、候选点选、翻页与长句输入。智能纠错沿用原设置默认关闭，使用时在“智能输入”中开启。
