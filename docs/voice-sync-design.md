# 声文对齐引擎设计

## 一、整体架构

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          FloatingService / VideoRecordActivity          │
│                              (管线编排层)                                │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌──────────────┐    ┌──────────────────┐    ┌──────────────────────┐  │
│  │  AudioCapture │───▶│  DoubaoAsrClient  │───▶│   VoiceSyncEngine    │  │
│  │  (16kHz PCM)  │    │  (WebSocket ASR)  │    │   (拼音匹配引擎)      │  │
│  └──────────────┘    └──────────────────┘    └──────┬───────────────┘  │
│                                                     │                   │
│                          ┌──────────────────────────┘                   │
│                          ▼                                              │
│                  ┌────────────────┐      ┌──────────────────────┐       │
│                  │ ScrollController│◀─────│   UI 更新管线         │       │
│                  │ (Spring动画滚动) │      │   highlight + debug   │       │
│                  └────────────────┘      └──────────────────────┘       │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 组件职责

| 组件 | 输入 | 输出 | 线程 |
|------|------|------|------|
| `AudioCapture` | 麦克风 PCM 音频 | 100ms 一包 16kHz PCM (320 bytes) | IO 协程 |
| `DoubaoAsrClient` | PCM 音频帧 | 识别文本 `(text, isFinal)` 回调 | WebSocket 回调 → `mainHandler.post` |
| `VoiceSyncEngine` | ASR 文本 `(text, isFinal)` | 更新后 `currentPosition` | `@Synchronized` 主线程 |
| `ScrollController` | 字符索引 `charIndex` | SpringAnimation 滚动 ScrollView | 主线程 |
| `updateHighlight` | 字符索引 `pos` | ForegroundColorSpan + BackgroundColorSpan | 主线程 |

---

## 二、数据流图

### 2.1 核心管线

```
麦克风音频流 (16kHz / 16-bit / 单声道)
    │
    ▼ 每 100ms 一包
┌──────────────────────────────────────────────────────────┐
│  AudioCapture.start(onChunk)                             │
│  ┌────────────────┐                                      │
│  │ 蓝牙 SCO 连接    │──── 成功 → AudioSource.VOICE_COMMUNICATION
│  │ 或手机麦克风      │──── 失败 → AudioSource.VOICE_RECOGNITION
│  └────────────────┘                                      │
│  while (isActive):                                       │
│    record.read(buffer) → onChunk(buffer.copyOf(read))    │
└────────────────────┬─────────────────────────────────────┘
                     │ PCM ByteArray
                     ▼
┌──────────────────────────────────────────────────────────┐
│  DoubaoAsrClient.sendAudio(pcm)                          │
│  ┌──────────────────────────────────────────────────┐    │
│  │  协议: [4B header][4B payloadSize][gzip(payload)] │    │
│  │  首包: FULL_CLIENT_REQUEST (JSON 配置参数)          │    │
│  │  音频包: AUDIO_ONLY_REQUEST (PCM 数据)              │    │
│  └──────────────────────────────────────────────────┘    │
│  WebSocket → 火山引擎 ASR 服务                             │
│      ↕                                                   │
│  parseServerResponse(bytes)                               │
│    → JSONObject.result.text  (识别文本)                    │
│    → JSONObject.result.definite (是否定稿)                 │
│    → onText(asrText, isFinal)                             │
└────────────────────┬─────────────────────────────────────┘
                     │ (text, isFinal)
                     ▼
┌──────────────────────────────────────────────────────────┐
│  FloatingService / VideoRecordActivity                    │
│  mainHandler.post {                                      │
│    val pos = syncEngine.onAsrIncrement(text, isFinal)    │
│    updateHighlight(pos)                                   │
│    scrollController.scrollToChar(pos)                     │
│    debugText.text = "「$text」"                            │
│  }                                                        │
└────────────────────┬─────────────────────────────────────┘
                     │ currentPosition
                     ▼
┌──────────────────────────────────────────────────────────┐
│  ScrollController.scrollToChar(charIndex)                │
│  ┌──────────────────────────────────────────────────┐    │
│  │  1. textView.layout.getLineForOffset(charIndex)  │    │
│  │  2. lineAbsY = paddingTop + lineTopInLayout      │    │
│  │  3. targetY = lineAbsY - visibleH × 30%         │    │
│  │  4. SpringAnimation.animateToFinalPosition(targetY)│   │
│  └──────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────┘
```

### 2.2 执行时序

```
AudioCapture    DoubaoAsrClient     VoiceSyncEngine     ScrollController    TextView
    │                 │                   │                   │               │
    │── PCM ───────▶  │                   │                   │               │
    │                 │── WebSocket ────▶ │                   │               │
    │                 │◀─ ASR text ────── │                   │               │
    │                 │── onText ───────▶ │                   │               │
    │                 │                   │── onAsrIncrement──│               │
    │                 │                   │   .cleanIn        │               │
    │                 │                   │   .calcDelta      │               │
    │                 │                   │   .updateBuffer   │               │
    │                 │                   │   .pinyinMatch    │               │
    │                 │                   │   .updatePosition │               │
    │                 │                   │── currentPosition─│               │
    │                 │                   │                   │── scrollToChar│
    │                 │                   │                   │   .getLineFor │
    │                 │                   │                   │   .springAnim │
    │                 │                   │                   │── scrollTo ──▶│
    │                 │                   │── updateHighlight─│──────────────▶│
    │                 │                   │                   │   Foreground  │
    │                 │                   │                   │   ↕ Background│
    │                 │                   │                   │   ColorSpan   │
    │                 │                   │                   │               │
    │── PCM ───────▶  │                   │                   │               │
    │                 │── WebSocket ────▶ │                   │               │
    │                 │◀─ ASR text ────── │                   │               │
    │                 │── onText ───────▶ │                   │               │
    │                 │                   │── onAsrIncrement──│               │
    │                 │                   │   ...             │               │
    │                 │                   │                   │               │
```

---

## 三、VoiceSyncEngine 内部结构

### 3.1 状态机

```
                                  ┌──────────┐
                                  │  初始状态  │
                                  │ pos = 0   │
                                  │ buf = []  │
                                  └─────┬─────┘
                                        │ onAsrIncrement
                                        ▼
                 ┌──────────────────────────────────────┐
                 │           ASR 文本预处理               │
                 │  cleanIn = 去标点 + 去空白              │
                 │  fullAccumulated 累积 / 替换            │
                 │  计算 delta = 新增文本部分               │
                 └────────────────┬─────────────────────┘
                                 │ delta 非空
                                 ▼
                 ┌──────────────────────────────────────┐
                 │         更新滑动窗口 Buffer             │
                 │  recentBuffer.append(delta chars)      │
                 │  if len > windowSize: 删除首部         │
                 └────────────────┬─────────────────────┘
                                 │ buffer >= 2 chars
                                 ▼
                 ┌──────────────────────────────────────┐
                 │           拼音匹配核心                  │
                 │  patternPinyin = toPinyin(buffer)      │
                 │  搜索范围: [pos-back, pos+lines*行]     │
                 │  foreach start in range:               │
                 │    score = similarity(a, b) - penalty  │
                 └────────────────┬─────────────────────┘
                                 │ bestScore
                                 ▼
              ┌───────────────────────────────────────────────────┐
              │                                                   │
         score >= 0.30                                     score < 0.30
         且位置前移                                              │
              │                                                   │
              ▼                                                   ▼
     ┌──────────────────┐                            ┌────────────────────┐
     │    匹配成功        │                            │     匹配失败        │
     │  cleanPosition =  │                            │  lastMatched=false  │
     │    bestCleanEndIdx │                            │  if score<0.35:     │
     │  currentPosition  │                            │    noMatchCount++   │
     │    = map(cleanPos) │                            │  if count>=3:       │
     │  noMatchCount = 0 │                            │    buffer.clear()   │
     │  lastMatched=true │                            └────────────────────┘
     └────────┬─────────┘
              │
              ▼
     ┌──────────────────┐
     │  UI 更新触发      │
     │  scrollToChar(pos)│
     │  updateHighlight  │
     └──────────────────┘
```

### 3.2 数据结构

```
VoiceSyncEngine 实例状态
══════════════════════════════════════════════════════════════════

原始文稿层:
  scriptChars: CharArray           ← "今天\n天气真好！"
  currentPosition: Int  (volatile) ← 当前匹配位置（原始索引）
  indexMapping: List<Int>          ← [0,1,2,4,5,6,7]  clean→original

无标点层（用于匹配）:
  cleanChars: List<Char>           ← ['今','天','天','气','真','好']
  cleanPinyin: List<String>        ← ["jin tian","tian","tian qi","zhen","hao"]
  cleanPosition: Int               ← 当前匹配位置（无标点索引）

ASR 累积层（用于计算增量）:
  fullAccumulated: StringBuilder   ← ASR 累积文本（去标点版）
  lastFullClean: String            ← 上一包完整文本

滑动窗口层（用于匹配）:
  recentBuffer: StringBuilder      ← 最近 windowSize 个字符
  windowSize: Int = 5              ← 窗口大小
  searchForward: Int = 60          ← 向前搜索范围
  searchBack: Int = 3              ← 向后搜索范围

匹配状态:
  consecutiveNoMatch: Int          ← 连续低置信度计数
  lastMatched / prevMatched        ← 最近两次是否匹配成功
  lastScore / prevScore            ← 最近两次匹配得分
  lastBuffer / prevBuffer          ← 最近两次匹配的 buffer 内容

转写导出层（独立于匹配）:
  transcriptFinalized: StringBuilder  ← 已定稿的转写文本
  transcriptInterim: String           ← 中间结果
  lastExportPacket: String            ← 上一包原始 ASR 文本（去重用）
```

---

## 四、核心算法流程

### 4.1 onAsrIncrement 详细执行流

```
onAsrIncrement(newText, isFinal)
  │
  ├── 1. recordTranscriptForExport(newText, isFinal)
  │     (独立于匹配逻辑，用于最终上传)
  │
  ├── 2. 预处理
  │     alignedText = stripPunctuationForAlignment(newText)
  │     cleanIn = alignedText.filter { !it.isWhitespace() }
  │     cleanPrev = fullAccumulated.filter { !it.isWhitespace() }
  │
  ├── 3. 维护 fullAccumulated
  │     case A: cleanIn.startsWith(cleanPrev) || cleanPrev.isEmpty()
  │         → 替换（ASR 前缀扩展 / 首包）
  │     case B: cleanPrev.startsWith(cleanIn)
  │         → 替换（ASR 回退）
  │     case C: 其他
  │         → 追加（ASR 全量改写）
  │
  ├── 4. 计算增量 delta
  │     cleanFull = fullAccumulated（去空白）
  │     if cleanFull.startsWith(lastFullClean)
  │         delta = cleanFull.substring(lastFullClean.length)
  │     else
  │         → 全量改写，跳过本次匹配，重置 lastFullClean
  │         → return currentPosition  (不触发滚动)
  │
  ├── 5. 更新滑动窗口
  │     for ch in delta:
  │         recentBuffer.append(ch)
  │     while recentBuffer.length > windowSize (5):
  │         recentBuffer.deleteCharAt(0)
  │     if recentBuffer.length < 2:
  │         → return currentPosition  (不足 2 字无法匹配)
  │
  ├── 6. 拼音匹配（核心）
  │     patternPinyin = recentBuffer.map { toPinyin(it) }
  │     searchStart = max(0, cleanPosition - searchBack)
  │     searchEnd = forwardEndByLines(cleanPosition, searchForwardLines)  ← 按行计算
  │
  │     for start in searchStart..searchEnd:
  │       scriptSubList = cleanPinyin.subList(start, start + patternLen)
  │       similarity(patternPinyin, scriptSubList)
  │         → for i in 0..min(a,b):
  │            完全相同: score +1.0, weight +1.0
  │            声母相同: score +0.2, weight +0.2
  │            其他: 0
  │         → avgScore = sum / len
  │         → return Pair(avgScore, weightedMatchCount)
  │
  │       if weightedMatch < 2: continue  (至少 2 个字声母对上)
  │       forwardDist = max(0, start - cleanPosition)
  │
  │       penalty = distancePenalty(forwardDist)
  │       score = rawScore - penalty
  │
  │       if score > bestScore:
  │         bestScore, bestCleanEndIdx, bestForwardDist = update
  │
  ├── 7. 更新位置
  │     保存 prev/last 调试信息
  │
  │     if bestScore >= 0.30 && bestCleanEndIdx > cleanPosition:
  │         cleanPosition = bestCleanEndIdx
  │         currentPosition = indexMapping[cleanPosition]
  │         consecutiveNoMatch = 0
  │         lastMatched = true
  │     else:
  │         lastMatched = false
  │         if bestScore < 0.35:
  │             consecutiveNoMatch++
  │             if consecutiveNoMatch >= 3:
  │                 recentBuffer.clear()      ← 低置信度清空 buffer
  │                 consecutiveNoMatch = 0
  │
  └── return currentPosition
```

### 4.2 距离惩罚函数

```
penalty(forwardDist)
  │
  ├── 0..2 字   → 0.0              (近处无惩罚)
  ├── 3..8 字   → (dist-2)/8*0.12  (最多 0.09)
  └── >8 字     → 0.12+(dist-8)/12*0.48 (上限约 0.60)

  图示（横轴=前向字数，纵轴=惩罚值）:
  ┌─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┐
  │     │     │     │     │     │     │     │     │     │
  0.60 ┤                                  ╱
  0.50 ┤                              ╱
  0.40 ┤                          ╱
  0.30 ┤                      ╱
  0.20 ┤                  ╱
  0.10 ┤           ╱──────
  0.00 ┼─┬─┬─────────────────────────────
      0  2  4  6  8 10 12 14 16 18 20
```

### 4.3 拼音转换（toPinyin）

```
toPinyin(ch: Char):
  │
  ├── ASCII (code < 128):
  │     → ch.lowercaseChar().toString()
  │
  ├── CJK 字符:
  │     PinyinHelper.toHanyuPinyinStringArray(ch, format)
  │     format: HanyuPinyinToneType.WITHOUT_TONE
  │     → 取第一个拼音结果 .lowercase()
  │
  └── 回退:
        → ch.toString()  (原样返回)
```

### 4.4 标点检测（isPunctuation）

```
isPunctuation(ch: Char):
  │  同文稿使用一致的标点集，确保对齐和文稿标点检测同步
  │
  ├── CJK 符号标点: 0x3000..0x303F
  │     (包括、。〃〄々〆〇〈〉《》「」『』【】〒〓〔〕〖〗〘〙〚〛〜〝〞〟〠〡〢〣〤〥〦〧〨〩〪〭〮〯〫〬〰〱〲〳〴〵〶〷〸〹〺〻〼〽〾〿)
  │
  ├── 全角/半角形式: 0xFF00..0xFFEF
  │     (包含全角字母数字标点)
  │
  └── ASCII 标点集合:
        , . ; : ? ! " ' ( ) [ ] < > - _ / \
```

---

## 五、转写导出系统

独立于匹配逻辑的并行系统：

```
recordTranscriptForExport(text, isFinal)
  │
  ├── 去重: if text == lastExportPacket → skip
  │
  ├── isFinal = true (定稿):
  │     提取新增部分（相对已有 finalized 内容）
  │     按换行拆分 → appendOneFinalizedParagraph(part)
  │     transcriptInterim = ""
  │
  ├── isFinal = false (中间结果):
  │     提取相对 finalized 的新增部分
  │     与现有 interim 做前缀比较决定替换/追加策略
  │
  └── accumulatedAsrTranscript():
        finalized + interim 合并
        → collapseTranscriptStaircaseLines() 压缩阶梯重复行
        → 返回最终转写文本

collapseTranscriptStaircaseLines 效果:
  输入: "first line\nfirst line extended\nfirst line extended more"
  输出: "first line extended more"
        (后一行是前一行的真超集时合并)
```

---

## 六、手动干预

### setPosition(originalCharIndex)

用户手动滚动后调用，重新锚定匹配位置：

```
setPosition(originalCharIndex)
  ├── currentPosition = originalCharIndex  (限制在文稿范围内)
  ├── 反向查找对应的 cleanPosition
  │     for i in indexMapping.indices:
  │       if indexMapping[i] <= currentPosition:
  │         cleanPosition = i
  │
  └── 清空累积状态（关键！不清则增量永远对不上新位置）:
        fullAccumulated.clear()
        lastFullClean = ""
        recentBuffer.clear()
        consecutiveNoMatch = 0
```

---

## 七、关键参数

| 参数 | 默认值 | 说明 | 调优方向 |
|------|--------|------|---------|
| `windowSize` | 5 | 滑动窗口大小，即每次匹配的字符数 | 越大越精确但越容易丢锁 |
| `searchForwardLines` | 2 | 向前搜索的行数 | 越少越不容易跳行，适合逐行朗读 |
| `searchBack` | 3 | 允许向后搜索的字数 | 处理 ASR 回退场景 |
| `forwardEndByLines` | — | 从当前 cleanPos 出发，查找 N 行后的边界 clean 索引 | 内部方法，通过 lineStarts + indexMapping 计算 |
| `matchThreshold` | 0.30 | 判定匹配的最低相似度 | 越小越易匹配但易漂移 |
| `lowConfidence` | 0.35 | 低于此值视为低置信度 | 越小越不易清 buffer |
| `flushAfter` | 3 | 连续低置信度次数后清 buffer | 越小清 buffer 越频繁 |
| `minWeightedMatch` | 2 | 至少加权匹配 2 个字符才考虑 | 防止单字偶然匹配 |

### 参数间交互

```
         lowConfidence
              │
              ▼
   得分 < 0.35 → consecutiveNoMatch++
                     │
                     ▼
              consecutiveNoMatch >= flushAfter (3)
                     │
                     ▼
              recentBuffer.clear()  ← 重新开始积累
                     │
                     ▼
        下次匹配从空 buffer 开始 → 相当于跳过
        若干字的 ASR 输出，等到 buffer 攒够再匹配
```

---

## 八、组件交互模式图

### 8.1 FloatingService 管线整合

```
FloatingService.setupPipeline(script, appId, accessToken)
  │
  ├── syncEngine = VoiceSyncEngine(script)
  │     内部预处理:
  │       cleanChars ← script.filter { !isPunctuation }
  │       cleanPinyin ← cleanChars.map { toPinyin }
  │       indexMapping ← clean→original 映射
  │
  ├── scrollController = ScrollController(scrollView, textView, script)
  │     内部:
  │       originalLineStarts ← parseOriginalLines(script)
  │       springAnim ← SpringAnimation(stiffness=LOW, damping=NO_BOUNCY)
  │
  ├── audioCapture = AudioCapture(context)
  │     .start(onChunk, onDevice, onStatus)
  │
  └── asrClient = DoubaoAsrClient(appId, accessToken, onText, onError)
       .connect()
       audioCapture 每 100ms → onChunk → asrClient.sendAudio(pcm)
       asrClient 收到响应 → onText(text, isFinal) → syncEngine.onAsrIncrement
```

### 8.2 VideoRecordActivity 管线整合（录制模式下）

```
VideoRecordActivity.startRecording()
  │
  ├── syncEngine.reset() 然后 新 VoiceSyncEngine(script)
  │     setPosition(startCharIndex)  ← 从 ScrollView 当前可见位置开始
  │
  ├── asrClient = DoubaoAsrClient(appId, accessToken, onText, onError)
  │     onText 回调:
  │       val pos = syncEngine.onAsrIncrement(text, isFinal)
  │       mainHandler.post {
  │         updateHighlight(pos)
  │         scrollController.scrollToChar(pos)
  │         debugText.text = "$mark [${lastBuffer}] ${lastScore}"
  │       }
  │
  ├── audioCapture = AudioCapture(this)
  │     .start(onChunk = { pcm → asrClient.sendAudio(pcm) })
  │
  └── 录制结束 → stopTeleprompter()
        audioCapture.stop() + asrClient.close()
        → 延迟 450ms → uploadTranscriptAfterRecording()
```

### 8.3 蓝牙按键滚动（录制模式下）

```
VideoRecordActivity.dispatchKeyEvent(event)
  │
  └── if 蓝牙按键 (VOLUME_UP/DOWN, CAMERA, MEDIA_PLAY_PAUSE, HEADSETHOOK):
        if 防抖 (< 300ms 间隔) → 忽略
        charIndex = scrollController.scrollOneLine()
        syncEngine.setPosition(charIndex)
        updateHighlight(charIndex)
```

---

## 九、UI 更新管线

```
updateHighlight(pos: Int)
  │
  ├── 获取 scriptText 的 Spannable
  │
  ├── 清除旧 span
  │     ForegroundColorSpan (金色)
  │     BackgroundColorSpan (白色)
  │
  ├── 已读部分 (0..pos): ForegroundColorSpan("#FFD700") 金色
  │
  └── 当前字 (pos..pos+1):
        BackgroundColorSpan(WHITE)  白色背景
        ForegroundColorSpan(BLACK)  黑色文字
```

---

## 十、设计要点总结

### 处理 ASR 特殊行为

| ASR 行为 | 应对策略 |
|----------|---------|
| `full` 模式返回完整文本 | 通过 `fullAccumulated` 前缀比较精确提取增量 |
| 同一识别重复下推 | `lastExportPacket` 去重 |
| ASR 回退（短→长→短） | `cleanPrev.startsWith(cleanIn)` 分支检测 |
| 全量改写（完全不同） | 跳过本次匹配，重置 `lastFullClean` |
| 同音别字（的/得/地） | 拼音声母匹配给 0.2 分 |
| 阶梯式完善文本 | `collapseTranscriptStaircaseLines` 压缩 |

### 追赶与防漂移

| 场景 | 机制 |
|------|------|
| 落后文稿 | 柔和距离惩罚（上限 0.60）允许远处匹配 |
| 连续丢锁 | 3 次低置信度后清空 buffer 重新累积 |
| 偶然误匹配 | 需同时满足 score≥0.30 + weightedMatch≥2 + 位置前移 |
| 手动翻页 | `setPosition` 清空全部累积状态 |

### 线程安全

- `VoiceSyncEngine.onAsrIncrement` + `setPosition` 标记 `@Synchronized`
- `currentPosition` 用 `@Volatile` 确保读取可见性
- ASR 回调通过 `mainHandler.post` 切回主线程
