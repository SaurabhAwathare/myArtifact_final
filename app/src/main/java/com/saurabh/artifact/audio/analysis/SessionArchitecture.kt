package com.saurabh.artifact.audio.analysis

import com.saurabh.artifact.model.ReflectionQuestion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * Real-time audio signal analysis implementation.
 */
class ProductionSignalProcessor @Inject constructor() : SignalProcessor {
    private val _emotionalState = MutableStateFlow(EmotionalState.CALM)
    override val emotionalState = _emotionalState.asStateFlow()

    private val _voiceEmotion = MutableStateFlow("")
    override val voiceEmotion = _voiceEmotion.asStateFlow()

    private val _voiceInsight = MutableStateFlow("")
    override val voiceInsight = _voiceInsight.asStateFlow()

    private val _timingState = MutableStateFlow(TimingState.FLOW)
    override val timingState = _timingState.asStateFlow()

    private val _timingSuggestion = MutableStateFlow<String?>(null)
    override val timingSuggestion = _timingSuggestion.asStateFlow()

    private val _isSilenceDetected = MutableStateFlow(false)
    override val isSilenceDetected = _isSilenceDetected.asStateFlow()

    private var silenceStartTime = 0L
    private var accumulatedEnergy = 0f
    private var energyCount = 0
    private var silenceFrequency = 0

    override fun process(amplitudes: List<Float>, duration: Long, lastInteractionTime: Long) {
        val currentAmplitude = amplitudes.lastOrNull() ?: 0f
        accumulatedEnergy += currentAmplitude
        energyCount++

        // Emotion Analysis
        val analyzerResult = AudioEmotionAnalyzer.analyze(amplitudes)
        _emotionalState.value = analyzerResult.state
        _voiceEmotion.value = analyzerResult.label
        _voiceInsight.value = analyzerResult.insight

        // Timing Logic
        val timingSignals = TimingSignals(
            currentSilenceDurationMs = if (silenceStartTime != 0L) System.currentTimeMillis() - silenceStartTime else 0L,
            sessionDurationSeconds = duration,
            speechEnergy = currentAmplitude,
            timeSinceLastInteractionMs = System.currentTimeMillis() - lastInteractionTime
        )
        _timingState.value = TimingIntelligenceEngine.evaluate(timingSignals)
        
        // Silence Tracking
        if (currentAmplitude < SILENCE_THRESHOLD) {
            if (silenceStartTime == 0L) silenceStartTime = System.currentTimeMillis()
        } else {
            if ((silenceStartTime != 0L) && ((System.currentTimeMillis() - silenceStartTime) > SILENCE_TRIGGER_MS)) {
                silenceFrequency++
            }
            silenceStartTime = 0L
        }
        _isSilenceDetected.value = (silenceStartTime != 0L) && ((System.currentTimeMillis() - silenceStartTime) > SILENCE_TRIGGER_MS)
    }

    override fun getDepthSignals(skipFrequency: Int, responseDurations: List<Long>, historicalDepth: Float): DepthSignals {
        return DepthSignals(
            avgSpeechEnergy = if (energyCount > 0) accumulatedEnergy / energyCount else 0f,
            silenceFrequency = silenceFrequency,
            avgResponseDurationSeconds = if (responseDurations.isNotEmpty()) responseDurations.average().toLong() else 0,
            skipFrequency = skipFrequency,
            historicalAvgDepth = historicalDepth
        )
    }

    override fun reset() {
        _emotionalState.value = EmotionalState.CALM
        _voiceEmotion.value = ""
        _voiceInsight.value = ""
        _timingState.value = TimingState.FLOW
        _timingSuggestion.value = null
        _isSilenceDetected.value = false
        silenceStartTime = 0L
        accumulatedEnergy = 0f
        energyCount = 0
        silenceFrequency = 0
    }

    companion object {
        private const val SILENCE_THRESHOLD = 0.05f
        private const val SILENCE_TRIGGER_MS = 4000L
    }
}

/**
 * Prompt flow management implementation.
 */
class ProductionFlowManager @Inject constructor(
    private val promptRepository: com.saurabh.artifact.repository.PromptRepository
) : FlowManager {
    private var sessionPlanner: SessionPlanner? = null
    private var flowController: FlowController? = null
    
    private val _currentPrompt = MutableStateFlow<ReflectionQuestion?>(null)
    override val currentPrompt = _currentPrompt.asStateFlow()

    private val _reflectionMode = MutableStateFlow(ReflectionMode.EXPRESS)
    override val reflectionMode = _reflectionMode.asStateFlow()

    private val _hasNext = MutableStateFlow(false)
    override val hasNext = _hasNext.asStateFlow()

    private val _usedQuestions = mutableListOf<ReflectionQuestion>()
    override val usedQuestions: List<ReflectionQuestion> get() = _usedQuestions.toList()

    override fun initialize(memory: ReflectionMemory, emotion: String?) {
        val initialDepthRange = when {
            memory.averageDepthPreference >= 3.0f -> 2..4
            memory.averageDepthPreference <= 1.5f -> 1..2
            else -> 1..3
        }
        sessionPlanner = SessionPlanner(memory, promptRepository.getAllQuestions())
        val plan = sessionPlanner!!.plan(emotion, initialDepthRange)
        flowController = FlowController(plan)
        
        _usedQuestions.clear()
        flowController?.currentPrompt?.let { 
            _currentPrompt.value = it
            _usedQuestions.add(it)
        }
        _hasNext.value = flowController?.hasNext ?: false
    }

    override fun advance(totalDuration: Long, isSilenceDetected: Boolean, signals: DepthSignals): String? {
        val controller = flowController ?: return null
        
        // Mode logic
        _reflectionMode.value = when {
            totalDuration < 60 -> ReflectionMode.EXPRESS
            totalDuration > 180 -> ReflectionMode.GROW
            else -> ReflectionMode.SHIFT
        }

        val transitionResult = controller.next(totalDuration)
        return if (transitionResult is FlowController.TransitionResult.Next) {
            _currentPrompt.value = transitionResult.question
            _usedQuestions.add(transitionResult.question)
            _hasNext.value = controller.hasNext
            transitionResult.transition
        } else null
    }

    override fun reset() {
        _currentPrompt.value = null
        _hasNext.value = false
        _usedQuestions.clear()
        flowController = null
    }
}

/**
 * Summary and Memory evolution implementation.
 */
class ProductionInsightManager @Inject constructor() : InsightManager {
    override suspend fun finalizeSession(
        amplitudes: List<Float>,
        usedQuestions: List<ReflectionQuestion>,
        durationSeconds: Long,
        memory: ReflectionMemory
    ): Pair<SessionSummary?, ReflectionMemory> {
        val generatedSummary = SummaryGenerator.generate(amplitudes, usedQuestions, durationSeconds)
        
        val updatedMemory = ReflectionMemory.updatedMemory(
            currentMemory = memory,
            usedQuestions = usedQuestions,
            finalDepthReached = usedQuestions.maxOfOrNull { it.depthLevel } ?: 1,
            energyPattern = ProgressAnalyzer.mapPattern(generatedSummary.flowObservation),
            durationSeconds = durationSeconds
        )

        val newRawInsight = ProgressAnalyzer.analyze(updatedMemory.history)
        val finalMemory = if (newRawInsight != null) {
            val evolvedInsight = InsightEvolutionEngine.evolve(newRawInsight, updatedMemory.insightHistory)
            updatedMemory.copy(
                pendingInsight = InsightScheduler.schedule(newRawInsight),
                insightHistory = (updatedMemory.insightHistory + evolvedInsight).takeLast(10)
            )
        } else updatedMemory

        return generatedSummary.copy(isEvolvedInsight = newRawInsight != null) to finalMemory
    }
}

interface SignalProcessor {
    val emotionalState: StateFlow<EmotionalState>
    val voiceEmotion: StateFlow<String>
    val voiceInsight: StateFlow<String>
    val timingState: StateFlow<TimingState>
    val timingSuggestion: StateFlow<String?>
    val isSilenceDetected: StateFlow<Boolean>
    fun process(amplitudes: List<Float>, duration: Long, lastInteractionTime: Long)
    fun getDepthSignals(skipFrequency: Int, responseDurations: List<Long>, historicalDepth: Float): DepthSignals
    fun reset()
}

interface FlowManager {
    val currentPrompt: StateFlow<ReflectionQuestion?>
    val reflectionMode: StateFlow<ReflectionMode>
    val hasNext: StateFlow<Boolean>
    val usedQuestions: List<ReflectionQuestion>
    fun initialize(memory: ReflectionMemory, emotion: String?)
    fun advance(totalDuration: Long, isSilenceDetected: Boolean, signals: DepthSignals): String?
    fun reset()
}

interface InsightManager {
    suspend fun finalizeSession(
        amplitudes: List<Float>,
        usedQuestions: List<ReflectionQuestion>,
        durationSeconds: Long,
        memory: ReflectionMemory
    ): Pair<SessionSummary?, ReflectionMemory>
}
