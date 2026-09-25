# 清简输入法 (QingJian IME)

类百度输入法的安卓 26 键全拼输入法。**简洁清爽 · 无广告 · 无跟踪 · 词库大**。

## 特性

- 26 键 QWERTY 全拼，自绘键盘（点按 / 滑动换键 / 按键气泡 / 首行长按数字）
- 大词库：38.6 万记录（pinyin-data 单字 + jieba 35 万词频），二进制索引微秒级查询
- 智能候选：多切分枚举 + 前缀预测 + Viterbi 单字组词兜底
- 整句级联上屏：选词后剩余拼音自动续算
- 用户词自学习（本机 SharedPreferences，绝不上传）
- 可选模糊音（zh/z、ch/c、sh/s、an/ang、en/eng、in/ing）
- 中文标点自动全角；回车上屏原始拼音；空格上屏首选
- 亮/暗色主题自动跟随系统
- 纯 Java + 框架 API，零第三方依赖，APK 约 4 MB

## 安装

1. 下载 [最新 Release](https://github.com/AIZIYOU466/qingyan-ime/releases/latest) 的 `app-release.apk` 安装
2. 系统设置 → 输入法 → 启用「清简输入法」
3. 切换默认输入法为「清简输入法」

## 构建

```bash
./gradlew assembleDebug          # 本机调试
./gradlew assembleRelease        # CI 已配签名 secrets
```

### 本机为 aarch64 的注意

`gradle.properties` 已设 `android.aapt2FromMavenOverride=/usr/bin/aapt2`
（Maven 下的 aapt2 是 x86_64，aarch64 主机需用系统 Debian 包的 aapt2 替代）。

## 词库重建

```bash
# 需要先准备 /tmp/lexicon/pinyin.tsv 和 /tmp/lexicon/jieba_dict.txt
python3 tools/build_lexicon.py   # 输出 app/src/main/assets/lexicon/lex.bin
```

## 目录结构

```
app/src/main/java/com/yathoughts/ime/
├── ImeService.java            输入法主服务（组合逻辑/上屏/编辑控制）
├── SettingsActivity.java      设置页（启用引导/偏好/关于）
├── engine/
│   ├── PinyinEngine.java      拼音引擎（切分/查询/模糊/排序/用户词）
│   ├── Lexicon.java           二进制词库加载与二分查询
│   ├── SyllableSplitter.java  音节切分（多切分枚举）
│   ├── UserDict.java          用户词自学习
│   └── UserDictHolder.java    引擎单例桥
└── ui/
    ├── KeyboardView.java      26 键自绘键盘
    └── CandidateView.java     候选栏
tools/
├── build_lexicon.py           词库生成器
└── EngineTest.java            引擎 JVM 验证
```
