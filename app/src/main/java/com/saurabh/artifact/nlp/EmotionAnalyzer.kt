package com.saurabh.artifact.nlp

import com.saurabh.artifact.model.Emotion
import com.saurabh.artifact.model.EmotionResult
import java.util.Locale

/**
 * A privacy-first, on-device NLP engine for emotional tone detection.
 * Uses weighted keyword matching for deterministic and fast execution.
 */
class EmotionAnalyzer {

    private enum class Valence { POSITIVE, NEGATIVE, NEUTRAL }

    private val emotionValence = mapOf(
        Emotion.HAPPY to Valence.POSITIVE,
        Emotion.MOTIVATED to Valence.POSITIVE,
        Emotion.HOPEFUL to Valence.POSITIVE,
        Emotion.GRATEFUL to Valence.POSITIVE,
        Emotion.CALM to Valence.POSITIVE,
        Emotion.SAD to Valence.NEGATIVE,
        Emotion.ANXIOUS to Valence.NEGATIVE,
        Emotion.ANGRY to Valence.NEGATIVE,
        Emotion.LONELY to Valence.NEGATIVE,
        Emotion.OVERWHELMED to Valence.NEGATIVE,
        Emotion.CONFUSED to Valence.NEUTRAL,
        Emotion.NEUTRAL to Valence.NEUTRAL,
        Emotion.MIXED to Valence.NEUTRAL,
        Emotion.UNCLEAR to Valence.NEUTRAL
    )

    private val emotionLexicon = mapOf(
        "en" to mapOf(
            Emotion.HAPPY to mapOf(
                "happy" to 1.0f, "joy" to 1.2f, "glad" to 0.8f, "wonderful" to 1.3f, "great" to 1.0f,
                "excellent" to 1.2f, "love" to 1.5f, "amazing" to 1.3f, "smile" to 0.7f, "blessed" to 1.1f,
                "good" to 0.6f, "better" to 0.5f, "delighted" to 1.4f, "cheerful" to 1.1f, "ecstatic" to 1.6f
            ),
            Emotion.SAD to mapOf(
                "sad" to 1.0f, "unhappy" to 1.1f, "cry" to 1.2f, "tears" to 1.0f, "pain" to 1.3f,
                "hurt" to 1.2f, "depressed" to 1.5f, "miserable" to 1.4f, "sorrow" to 1.3f, "grief" to 1.6f,
                "broken" to 1.2f, "heartbreak" to 1.5f, "lonely" to 0.9f, "hopeless" to 1.4f, "gloomy" to 1.0f
            ),
            Emotion.LONELY to mapOf(
                "lonely" to 1.2f, "alone" to 0.8f, "isolated" to 1.3f, "empty" to 1.1f, "miss" to 0.7f,
                "forgotten" to 1.2f, "abandoned" to 1.4f, "ignored" to 1.1f, "solitude" to 0.6f, "unwanted" to 1.3f
            ),
            Emotion.ANXIOUS to mapOf(
                "anxious" to 1.2f, "worried" to 1.0f, "nervous" to 0.9f, "scared" to 1.3f, "fear" to 1.4f,
                "panic" to 1.6f, "stress" to 1.1f, "uneasy" to 0.8f, "overwhelmed" to 1.2f, "pressure" to 1.0f,
                "uncertain" to 0.9f, "dread" to 1.5f, "shaking" to 1.1f, "tense" to 1.0f
            ),
            Emotion.ANGRY to mapOf(
                "angry" to 1.2f, "mad" to 1.1f, "furious" to 1.6f, "hate" to 1.5f, "rage" to 1.7f,
                "annoyed" to 0.7f, "frustrated" to 1.0f, "bitter" to 1.2f, "upset" to 0.8f, "resent" to 1.3f,
                "offended" to 1.1f, "irritable" to 0.9f, "outraged" to 1.5f
            ),
            Emotion.MOTIVATED to mapOf(
                "motivated" to 1.2f, "inspired" to 1.3f, "ready" to 0.8f, "excited" to 1.1f, "strong" to 1.0f,
                "power" to 1.1f, "goal" to 0.9f, "achieve" to 1.2f, "hope" to 1.0f, "future" to 0.7f,
                "possible" to 0.6f, "determined" to 1.4f, "confident" to 1.3f, "success" to 1.2f
            ),
            Emotion.HOPEFUL to mapOf(
                "hopeful" to 1.2f, "optimistic" to 1.3f, "looking forward" to 1.0f, "bright" to 0.8f,
                "expectant" to 0.9f, "reassuring" to 1.1f, "promise" to 1.0f
            ),
            Emotion.CALM to mapOf(
                "calm" to 1.2f, "peaceful" to 1.4f, "serene" to 1.5f, "relaxed" to 1.1f, "tranquil" to 1.3f,
                "quiet" to 0.7f, "still" to 0.6f, "rested" to 1.0f
            ),
            Emotion.GRATEFUL to mapOf(
                "grateful" to 1.5f, "thankful" to 1.4f, "appreciation" to 1.2f, "blessed" to 1.1f,
                "thanks" to 0.8f, "indebted" to 0.9f
            ),
            Emotion.OVERWHELMED to mapOf(
                "overwhelmed" to 1.4f, "buried" to 1.2f, "drowning" to 1.5f, "too much" to 1.1f,
                "loaded" to 0.9f, "exhausted" to 1.3f
            ),
            Emotion.CONFUSED to mapOf(
                "confused" to 1.2f, "lost" to 1.0f, "puzzled" to 1.3f, "disoriented" to 1.4f,
                "clueless" to 1.1f, "unsure" to 0.9f, "mixed up" to 1.0f
            )
        ),
        "es" to mapOf(
            Emotion.HAPPY to mapOf(
                "feliz" to 1.0f, "alegre" to 1.1f, "contento" to 0.8f, "maravilloso" to 1.3f, "genial" to 1.0f,
                "excelente" to 1.2f, "amor" to 1.5f, "increíble" to 1.3f, "sonrisa" to 0.7f, "bendecido" to 1.1f,
                "bien" to 0.6f, "mejor" to 0.5f
            ),
            Emotion.SAD to mapOf(
                "triste" to 1.0f, "infeliz" to 1.1f, "llorar" to 1.2f, "lágrimas" to 1.0f, "dolor" to 1.3f,
                "lastimado" to 1.2f, "deprimido" to 1.5f, "miserable" to 1.4f, "pena" to 1.1f, "roto" to 1.2f,
                "desamor" to 1.3f
            ),
            Emotion.LONELY to mapOf(
                "solo" to 0.9f, "aislado" to 1.3f, "vacío" to 1.1f, "extrañar" to 0.7f, "olvidado" to 1.2f,
                "abandonado" to 1.4f, "ignorado" to 1.1f, "soledad" to 0.8f
            ),
            Emotion.ANXIOUS to mapOf(
                "ansioso" to 1.2f, "preocupado" to 1.0f, "nervioso" to 0.9f, "asustado" to 1.3f, "miedo" to 1.4f,
                "pánico" to 1.6f, "estrés" to 1.1f, "inquieto" to 0.8f, "abrumado" to 1.2f, "presión" to 1.0f,
                "incierto" to 0.9f
            ),
            Emotion.ANGRY to mapOf(
                "enojado" to 1.2f, "enfadado" to 1.1f, "furioso" to 1.6f, "odio" to 1.5f, "rabia" to 1.7f,
                "molesto" to 0.7f, "frustrado" to 1.0f, "amargo" to 1.2f, "disgustado" to 0.8f
            ),
            Emotion.MOTIVATED to mapOf(
                "motivado" to 1.2f, "inspirado" to 1.3f, "listo" to 0.8f, "emocionado" to 1.1f, "fuerte" to 1.0f,
                "poder" to 1.1f, "meta" to 0.9f, "lograr" to 1.2f, "esperanza" to 1.0f, "futuro" to 0.7f,
                "posible" to 0.6f
            ),
            Emotion.HOPEFUL to mapOf(
                "esperanzado" to 1.2f, "optimista" to 1.3f, "ilusión" to 1.0f, "brillante" to 0.8f,
                "promesa" to 1.0f
            ),
            Emotion.CALM to mapOf(
                "calma" to 1.2f, "paz" to 1.4f, "sereno" to 1.5f, "relajado" to 1.1f, "tranquilo" to 1.3f,
                "quieto" to 0.7f, "descansado" to 1.0f
            ),
            Emotion.GRATEFUL to mapOf(
                "agradecido" to 1.5f, "gracias" to 0.8f, "bendecido" to 1.1f
            ),
            Emotion.OVERWHELMED to mapOf(
                "abrumado" to 1.4f, "saturado" to 1.2f, "agotado" to 1.3f
            ),
            Emotion.CONFUSED to mapOf(
                "confundido" to 1.2f, "perdido" to 1.0f, "desorientado" to 1.4f, "duda" to 0.9f
            )
        )
    )


    private val negationWords = setOf(
        "not", "never", "no", "don't", "didn't", "cannot", "can't", "neither", "nor", "nothing", "nobody", "none",
        "no", "ni", "nada", "nunca", "tampoco", "jamás"
    )

    private val boosterWords = mapOf(
        "very" to 1.5f, "extremely" to 2.0f, "really" to 1.3f, "so" to 1.2f, "totally" to 1.4f, "highly" to 1.4f,
        "slightly" to 0.6f, "barely" to 0.5f, "hardly" to 0.5f,
        "muy" to 1.5f, "extremadamente" to 2.0f, "realmente" to 1.3f, "tan" to 1.2f, "totalmente" to 1.4f,
        "un poco" to 0.6f, "apenas" to 0.5f
    )

    fun analyze(text: String, locale: Locale = Locale.getDefault()): EmotionResult {
        if (text.isBlank()) return EmotionResult(Emotion.NEUTRAL, 0.0f)

        val langCode = locale.language
        val lexicon = emotionLexicon[langCode] ?: emotionLexicon["en"] ?: emptyMap()

        // Split into words but keep case for intensity detection
        val rawWords = text.split(Regex("[\\s\\p{Punct}]+")).filter { it.isNotBlank() }
        if (rawWords.isEmpty()) return EmotionResult(Emotion.NEUTRAL, 0.0f)

        val scores = mutableMapOf<Emotion, Float>()
        var globalMultiplier = 1.0f

        // Check for shouting (if entire text is uppercase and more than one word)
        if (rawWords.size > 1 && text == text.uppercase(locale) && text.any { it.isLetter() }) {
            globalMultiplier *= 1.5f
        }

        // Check for exclamation marks
        val exclamationCount = text.count { it == '!' }
        globalMultiplier *= (1.0f + (exclamationCount.coerceAtMost(3) * 0.1f))

        var i = 0
        while (i < rawWords.size) {
            val rawWord = rawWords[i]
            val word = rawWord.lowercase(locale)
            
            var wordMultiplier = globalMultiplier
            
            // Check if word is capitalized compared to surrounding (intensity boost)
            if (rawWord == rawWord.uppercase(locale) && rawWord.length > 1) {
                wordMultiplier *= 1.2f
            }

            // Lookahead for negations (simplified: affects next 2 words)
            if (negationWords.contains(word)) {
                // Peek next word to see if it's an emotion, then skip it
                if (i + 1 < rawWords.size) {
                    val nextWord = rawWords[i+1].lowercase(locale)
                    var isEmotion = false
                    for ((_, keywords) in lexicon) {
                        if (keywords.containsKey(nextWord)) {
                            isEmotion = true
                            break
                        }
                    }
                    if (isEmotion) {
                        i += 2
                        continue
                    }
                }
                i++
                continue 
            }

            // Lookahead for boosters
            val boosterVal = boosterWords[word]
            if (boosterVal != null) {
                wordMultiplier *= boosterVal
                // Peek next word to see if it's an emotion
                if (i + 1 < rawWords.size) {
                    val nextWord = rawWords[i+1].lowercase(locale)
                    var matchFound = false
                    for ((emotion, keywords) in lexicon) {
                        val weight = keywords[nextWord]
                        if (weight != null) {
                            scores[emotion] = (scores[emotion] ?: 0f) + (weight * wordMultiplier)
                            matchFound = true
                        }
                    }
                    if (matchFound) {
                        i += 2 // Skip the next word since we processed it
                        continue
                    }
                }
            }

            // Standard emotion check
            for ((emotion, keywords) in lexicon) {
                val weight = keywords[word]
                if (weight != null) {
                    scores[emotion] = (scores[emotion] ?: 0f) + (weight * wordMultiplier)
                }
            }
            i++
        }

        val topEmotionEntry = scores.maxByOrNull { it.value }
        val totalScore = scores.values.sum()
        val wordCount = rawWords.size

        // Fallback for identified but weak or non-existent signals
        if (topEmotionEntry == null || topEmotionEntry.value < 0.1f) {
            return if (wordCount >= 5) {
                EmotionResult(Emotion.UNCLEAR, 0.0f)
            } else {
                EmotionResult(Emotion.NEUTRAL, 0.0f)
            }
        }

        // MIXED DETECTION: Only if valence crossing occurs
        val sortedScores = scores.entries.sortedByDescending { it.value }
        if (sortedScores.size >= 2) {
            val topEmotion = sortedScores[0].key
            val secondEmotion = sortedScores[1].key
            
            val topValence = emotionValence[topEmotion] ?: Valence.NEUTRAL
            val secondValence = emotionValence[secondEmotion] ?: Valence.NEUTRAL
            
            // Check if top two emotions have different valence (Positive vs Negative)
            val isValenceCrossing = (topValence == Valence.POSITIVE && secondValence == Valence.NEGATIVE) ||
                                    (topValence == Valence.NEGATIVE && secondValence == Valence.POSITIVE)

            if (isValenceCrossing) {
                val topScore = sortedScores[0].value
                val secondScore = sortedScores[1].value
                val margin = (topScore - secondScore) / totalScore
                
                if (margin < 0.25f) { // Slightly wider margin for mixed valence
                    // Confidence is high if scores are balanced
                    val balance = 1.0f - margin
                    return EmotionResult(Emotion.MIXED, balance.coerceIn(0.5f, 0.9f))
                }
            }
        }

        // NEUTRAL fallback for identified but weak signals
        if (topEmotionEntry.value < 0.2f) {
            return EmotionResult(Emotion.NEUTRAL, 0.1f)
        }

        // Confidence is ratio of top emotion vs others, capped at 1.0
        val confidence = (topEmotionEntry.value / totalScore).coerceIn(0.1f, 1.0f)

        return EmotionResult(topEmotionEntry.key, confidence)
    }
}

