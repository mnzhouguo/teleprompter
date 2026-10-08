package com.example.teleprompter

data class VoiceSyncDiagnostics(
    val rawTextLength: Int = 0,
    val cleanTextLength: Int = 0,
    val deltaLength: Int = 0,
    val bufferLength: Int = 0,
    val previousPosition: Int = 0,
    val currentPosition: Int = 0,
    val cleanPosition: Int = 0,
    val localSearchStart: Int = 0,
    val localSearchEnd: Int = 0,
    val localBestScore: Double = 0.0,
    val localBestEnd: Int = 0,
    val localMatched: Boolean = false,
    val jumpAttempted: Boolean = false,
    val jumpAccepted: Boolean = false,
    val jumpScore: Double = 0.0,
    val jumpPatternLength: Int = 0,
    val jumpDistance: Int = 0,
    val jumpThreshold: Double = 0.0,
    val consecutiveNoMatch: Int = 0,
    val matched: Boolean = false,
    val decision: String = "idle"
)
