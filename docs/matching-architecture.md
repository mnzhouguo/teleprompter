# 声文匹配引擎内存架构图

## 一、初始化阶段（构造时一次性分配）

```
VoiceSyncEngine(script)
                         ┌─────────────────────────────────────────────┐
  script (String) ──────→│  scriptChars: CharArray (全文)              │ ◄── 固定
                         │  matchSurfaceChars: List<Char> (去标点)     │ ◄── 固定
                         │  matchSurfacePinyin: List<String> (拼音)    │ ◄── 固定
                         │  positionMapping: List<Int> (位置映射表)    │ ◄── 固定
                         │  lineStarts: List<Int> (非空行起始索引)     │ ◄── 固定
                         │─────────────────────────────────────────────│
                         │  asrAccumulated: StringBuilder = ""         │ ◄── 空
                         │  lastCleanAsr: String = ""                  │ ◄── 空
                         │  voiceSegmentBuffer: StringBuilder = ""     │ ◄── 空 (最大5字)
                         │  transcriptFinalized: StringBuilder = ""    │ ◄── 空
                         │  transcriptInterim: String = ""             │ ◄── 空
                         │─────────────────────────────────────────────│
                         │  scriptPosition: Int = 0                    │
                         │  matchPosition: Int = 0                     │
                         │  followLossCount: Int = 0                   │
                         └─────────────────────────────────────────────┘
```

## 二、录制中：每包 ASR 处理流程

### 第 N 包到达

```
  ASR 原始 text: "前前后后做了四次的知识复合工具"
        │
        ▼
  ┌─ recordTranscript(text, isFinal) ─────────────────────┐
  │  原始文本 → transcriptFinalized / transcriptInterim    │  ◄── 只增不减（转写导出用）
  │  去除包间重复(compactForDelta + startsWith 比较)      │
  └───────────────────────────────────────────────────────┘
        │
        ▼
  ┌─ stripPunctuation(text) ──────────────────────────────┐
  │  去标点、去空白                                        │
  │  "前前后后做了四次的知识复合工具"                        │
  └───────────────────────────────────────────────────────┘
        │
        ▼
  ┌─ ASR 增量累积 ────────────────────────────────────────┐
  │                                                       │
  │  matchInput = 当前包(去空白)                           │
  │  matchPrev  = asrAccumulated(去空白)                   │
  │                                                       │
  │  判断:                                                │
  │  ├─ matchInput startsWith matchPrev ─→ 替换累积       │
  │  │   例: asrAccumulated = "前前后后做了四次的知识"      │
  │  │       matchInput    = "前前后后做了四次的知识复合工具" │
  │  │       → 替换: 累积 = "前前后后做了四次的知识复合工具"   │  ◄── 正常 full 模式
  │  │                                                    │
  │  ├─ matchPrev startsWith matchInput ─→ 替换累积       │
  │  │   例: 新包较短(ASR 下发精简版) → 直接用新文本        │
  │  │                                                    │
  │  └─ 都不成立 ─→ 追加到累积                             │
  │      例: ASR 修正前面字词                              │
  │      旧累积: "前前后后做了四次的知识"                    │
  │      新包:   "前前后后做了四遍的知识复合工具"              │
  │      "做了四次" ≠ "做了四遍" → 不构成前缀               │
  │      → asrAccumulated 污染:                            │  ◄── 问题：累积无界增长
  │        "前前后后做了四次的知识前前后后做了四遍的知识复合工具" │
  └───────────────────────────────────────────────────────┘
        │
        ▼
  ┌─ 增量提取 ────────────────────────────────────────────┐
  │                                                       │
  │  matchFull = asrAccumulated(去空白).toString            │
  │                                                       │
  │  matchFull startsWith lastCleanAsr?                    │
  │  ├─ YES → delta = matchFull 新增部分                    │  ◄── 正常
  │  └─ NO  → lastCleanAsr = matchFull                     │
  │            清空 voiceSegmentBuffer                      │  ◄── 丢锁！
  │            返回当前位置，无推进                          │
  └───────────────────────────────────────────────────────┘
        │
        ▼
  ┌─ 语音片段更新 ────────────────────────────────────────┐
  │                                                       │
  │  delta 的每个字符 → voiceSegmentBuffer                  │
  │  超出 voiceSegmentMaxSize(5) → 挤出最早字符              │  ◄── 窗口大小限制
  │                                                       │
  │  例: delta = "复合工具的迭代升级"                        │
  │  buffer 增长: 复 → 复合 → 复合工 → ... → 复合工具的迭代升级  │
  │  截断到5字: "的迭代升级" (丢掉了前面的"复合工具")         │  ◄── 信息丢失！
  └───────────────────────────────────────────────────────┘
        │
        ▼
  ┌─ 滑动匹配 ────────────────────────────────────────────┐
  │                                                       │
  │  搜索窗口:                                             │
  │  [matchPosition - backwardSearchChars(3)               │
  │          → computeSearchEnd(matchPosition, 2行)]       │
  │                                                       │
  │  范围大小 ≈ 2~4 行 × 行字数                             │
  │                                                       │
  │  逐位滑动: patternPinyin(5字) vs matchSurfacePinyin     │
  │      ↓                                                │
  │  最佳匹配 (拼音相似度 - 距离惩罚)                        │
  │      ↓                                                │
  │  score ≥ 0.30 且 position 有推进?                       │
  │  ├─ YES → matchPosition = 新位置                        │
  │  │        scriptPosition = positionMapping[matchPosition]│
  │  │        followLossCount = 0                           │
  │  │        → 驱动 ScrollController 滚动                   │
  │  │                                                      │
  │  └─ NO  → lastMatched = false                           │
  │           score < 0.35?                                 │
  │           ├─ YES → followLossCount++                    │
  │           │   ≥ 3? → 清空 voiceSegmentBuffer            │  ◄── 最终丢锁
  │           │           followLossCount = 0               │
  │           └─ NO  → followLossCount = 0                  │
  └───────────────────────────────────────────────────────┘
```

## 三、数据增长趋势（全文朗读过程）

```
数据量
  │
  │  scriptChars (固定) ─────────────────────────────────────────
  │  matchSurfacePinyin (固定) ─────────────────────────────────
  │  positionMapping (固定) ───────────────────────────────────
  │
  │                          ┌─ transcriptFinalized (只增不减)
  │                         ╱
  │  asrAccumulated ───────●──●──●──●──●──● (阶梯增长，偶尔污染后跳增)
  │                      ╱  ╱  ╱  ╱  ╱  ╱
  │  lastCleanAsr ──────●──●──●──●──●──● (随累积同步增长)
  │
  │  voiceSegmentBuffer ──┴──┴──┴──┴──┴── (始终≤5，但会因丢锁清空)
  │
  │  scriptPosition ─────●──●──●──●──●──● (向右推进)
  │                      ╲  ╲  ╲     ╲     ← 读到这里跟不上了
  │  matchPosition ──────●──●──●──x─────── (卡住不动)
  │
  │                                     time ▶
  │
  │  ─── 前半段 ───│─── 后半段（滞后出现）───
  │
  │  原因:
  │  A. asrAccumulated 越长, 增量提取的 startsWith 越容易失败
  │  B. voiceSegmentMaxSize=5 导致增量信息大量丢失
  │  C. 仅搜2行, 一旦落后就找不到匹配
  │  D. 距离惩罚阻止跳跃追赶
  │  E. 3次低分 → 清空语音片段 → 彻底丢锁
```

## 四、状态机：正常 vs 滞后

```
                    ┌──────────────┐
                    │   跟随锁定    │
                    │ (locked)     │◄────────────────────────────┐
                    └──────┬───────┘                             │
                           │                                      │
                   匹配成功, 位置推进                                │
                           │                                      │
                           ▼                                      │
                    ┌──────────────┐                              │
                    │  ASR 增量提取 │                              │
                    │  (delta > 0) │                              │
                    └──────┬───────┘                              │
                           │                                      │
                           ▼                                      │
                    ┌──────────────┐    匹配失败 (score < 0.30)    │
                    │   滑动匹配    │─────────────────────────┐    │
                    │  (5字 2行)   │                          │    │
                    └──────┬───────┘                          ▼    │
                           │                            ┌──────────┴──┐
                           │                            │ 失配计数+1   │
                           │                            │ followLoss  │
                           │                            └──────┬──────┘
                           │                                   │
                           │                            ┌──────▼──────┐
                           │                            │ ≥ 3次?      │
                           │                            ├─ YES ─→ 清空buffer
                           │                            │         重置计数
                           │                            │         ──→ 彻底丢锁
                           │                            └─ NO ─→ 继续
                           │
                    ┌──────▼───────┐
                    │ 位置推进      │
                    │ scriptPosition│
                    │ matchPosition │
                    └──────┬───────┘
                           │
                           ▼
                    ┌──────────────┐
                    │ ScrollController│
                    │ 滚动到新位置   │
                    └──────────────┘

    ★ 后半段典型死循环:

    ASR包 → 累积污染 → startsWith失败 → 清空buffer → 返回原位
    ASR包 → 增量提取 → voiceSegment(新5字) → 搜2行 → 搜不到 → 失配
    ASR包 → 同上 → 3次 → 清空buffer → followLoss=0
    ASR包 → 重新开始累积 → 重复上述循环
              ↑
        位置从未推进！用户继续读，引擎卡住
```

## 五、各对象内存状态总结

| 对象 | 类型 | 大小变化 | 何时重置 | 问题 |
|------|------|----------|----------|------|
| `scriptChars` | `CharArray` | 固定 | 永不 | 无 |
| `matchSurfaceChars` | `List<Char>` | 固定 | 永不 | 无 |
| `matchSurfacePinyin` | `List<String>` | 固定 | 永不 | 无 |
| `positionMapping` | `List<Int>` | 固定 | 永不 | 无 |
| `lineStarts` | `List<Int>` | 固定 | 永不 | 无 |
| `asrAccumulated` | `StringBuilder` | 随朗读增长 | `reset()` / `setPosition()` | **无界增长，污染后恢复困难** |
| `lastCleanAsr` | `String` | 随前一项增长 | 同上 | **同上** |
| `voiceSegmentBuffer` | `StringBuilder` | 0~5 | 丢锁时 / `reset()` | **5 字太短，信息丢失** |
| `transcriptFinalized` | `StringBuilder` | 随朗读增长 | `reset()` / `setPosition()` | 无（仅导出用） |
| `transcriptInterim` | `String` | 0~数百字 | 每次 final 包 | 无 |
| `matchPosition` | `Int` | 递增 | `reset()` / `setPosition()` | **落后后追不上** |
| `followLossCount` | `Int` | 0~3 | 每次清空 | 无（设计如此） |
```
