package com.nyora.hasan72341.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import dagger.hilt.android.qualifiers.ApplicationContext
import com.nyora.hasan72341.ai.data.AiRepository
import com.nyora.hasan72341.ai.data.ChatMessage
import com.nyora.hasan72341.ai.data.ChapterTranslationRequest
import com.nyora.hasan72341.ai.data.ChapterTranslationResponse
import com.nyora.hasan72341.ai.data.DialogueBlock
import com.nyora.hasan72341.ai.engine.OcrProvider
import com.nyora.hasan72341.ai.engine.StoryBrain
import com.nyora.hasan72341.core.network.BaseHttpClient
import com.nyora.hasan72341.core.prefs.AppSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import com.nyora.hasan72341.translator.Language
import com.nyora.hasan72341.translator.Translator
import com.nyora.hasan72341.tachiyomi.network.await
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

enum class TranslationState {
	TRANSLATING, // OCR found, waiting for MT
	DOWNLOADING_MODELS, // Downloading language pack
	MT,          // Fast MT available
	REFINED      // LLM polished available
}

data class TranslationUpdate(
	val updates: Map<String, String>,
	val state: TranslationState
)

data class TranslatedBlock(
	val id: String,
	val originalText: String,
	val translatedText: String,
	val boundingBox: Rect,
	val state: TranslationState,
	val backgroundColor: Int = Color.WHITE
)

@Singleton
class MangaTranslator @Inject constructor(
	@ApplicationContext private val context: Context,
	private val aiRepository: AiRepository,
	private val ocrProvider: OcrProvider,
	private val storyBrain: StoryBrain,
	private val settings: AppSettings,
	@BaseHttpClient private val okHttp: OkHttpClient
) {
	private companion object {
		const val BATCH_DEBOUNCE_MS = 1200L
		const val MAX_BATCH_DIALOGUES = 12
	}

	private val chapterCache = ConcurrentHashMap<Long, ConcurrentHashMap<String, String>>()
	private val mtCache = ConcurrentHashMap<Long, ConcurrentHashMap<String, String>>()
	private val pageBlocksCache = ConcurrentHashMap<Long, ConcurrentHashMap<Int, List<TranslatedBlock>>>()
	private val translationFlows = ConcurrentHashMap<Long, MutableSharedFlow<TranslationUpdate>>()
	private val chapterRefinementJobs = ConcurrentHashMap<Long, Job>()
	private val activeRefinementChapters = ConcurrentHashMap.newKeySet<Long>()
	private val refinedTextCache = ConcurrentHashMap<String, String>()
	private val translatorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val googleTranslator = Translator()
	private val json = Json { ignoreUnknownKeys = true }

	fun getTranslationFlow(chapterId: Long): SharedFlow<TranslationUpdate> {
		return getMutableTranslationFlow(chapterId)
	}

	private fun getMutableTranslationFlow(chapterId: Long): MutableSharedFlow<TranslationUpdate> {
		return translationFlows.getOrPut(chapterId) {
			MutableSharedFlow(
				replay = 1,
				extraBufferCapacity = 64,
				onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
			)
		}
	}

	fun clearChapterContext(chapterId: Long) {
		chapterCache.remove(chapterId)
		translationFlows.remove(chapterId)
		mtCache.remove(chapterId)
		pageBlocksCache.remove(chapterId)
		chapterRefinementJobs.remove(chapterId)?.cancel()
		activeRefinementChapters.remove(chapterId)
		storyBrain.clear()
	}

	fun hasChapterCached(chapterId: Long): Boolean {
		val map = chapterCache[chapterId]
		return map != null && map.isNotEmpty()
	}

	// Constant scale factor to help ML Kit read small variable-sized text
	private val OCR_SCALE_FACTOR = 1.5f

	fun translatePage(chapterId: Long, pageIndex: Int, bitmap: Bitmap): Flow<List<TranslatedBlock>> = channelFlow {
		try {
			val cachedBlocks = pageBlocksCache[chapterId]?.get(pageIndex)
			if (cachedBlocks != null) {
				val updatedBlocks = cachedBlocks.map { block ->
					val cachedRefined = chapterCache[chapterId]?.get(block.id)
					val cachedMt = mtCache[chapterId]?.get(block.id)
					
					val text = cachedRefined ?: cachedMt ?: block.translatedText
					val state = when {
						cachedRefined != null -> TranslationState.REFINED
						cachedMt != null -> TranslationState.MT
						else -> block.state
					}
					block.copy(translatedText = text, state = state)
				}
				
				send(updatedBlocks)
				
				val allRefined = updatedBlocks.all { it.state == TranslationState.REFINED }
				if (allRefined) {
					return@channelFlow
				}
				
				val flowJob = launch {
					getTranslationFlow(chapterId).collect { update ->
						pageBlocksCache[chapterId]?.get(pageIndex)?.let { currentList ->
							val newList = currentList.map { block ->
								update.updates[block.id]?.let { 
									if (update.state.ordinal >= block.state.ordinal) {
										if (update.state == TranslationState.REFINED) {
											mtCache[chapterId]?.remove(block.id)
										}
										block.copy(translatedText = it, state = update.state)
									} else {
										block
									}
								} ?: block
							}
							pageBlocksCache[chapterId]?.put(pageIndex, newList)
							send(newList)
						}
					}
				}
				
				awaitClose { flowJob.cancel() }
				return@channelFlow
			}

			val processedForOcr = preprocessBitmapForOcr(bitmap, OCR_SCALE_FACTOR)
			val image = InputImage.fromBitmap(processedForOcr, 0)
			
			// Google-level Vision: Ensemble OCR with script-aware selection
			val ocrResult = ocrProvider.runEnsembleOcr(image)
			processedForOcr.recycle()
			
			if (ocrResult.blocks.isEmpty()) {
				send(emptyList()); return@channelFlow
			}

			// Scale bounding boxes back down to original image dimensions
			val scaledBlocks = ocrResult.blocks.map { block ->
				block.copy(
					boundingBox = Rect(
						(block.boundingBox.left / OCR_SCALE_FACTOR).toInt(),
						(block.boundingBox.top / OCR_SCALE_FACTOR).toInt(),
						(block.boundingBox.right / OCR_SCALE_FACTOR).toInt(),
						(block.boundingBox.bottom / OCR_SCALE_FACTOR).toInt()
					)
				)
			}

			val mergedBubbles = mergeBlocksIntoBubbles(scaledBlocks)
			
			val initialBlocks = mergedBubbles.mapIndexed { bIndex, bubble ->
				val id = "p${pageIndex}_b${bIndex}"
				val cachedRefined = chapterCache[chapterId]?.get(id)
				val cachedMt = mtCache[chapterId]?.get(id)
				
				val text = cachedRefined ?: cachedMt ?: ""
				val state = when {
					cachedRefined != null -> TranslationState.REFINED
					cachedMt != null -> TranslationState.MT
					else -> TranslationState.TRANSLATING
				}
				
				TranslatedBlock(
					id = id,
					originalText = bubble.text,
					translatedText = text,
					boundingBox = bubble.box,
					state = state,
					backgroundColor = sampleBackgroundColor(bitmap, bubble.box)
				)
			}

			pageBlocksCache.getOrPut(chapterId) { ConcurrentHashMap() }[pageIndex] = initialBlocks
			send(initialBlocks)

			val currentBlocksState = MutableStateFlow(initialBlocks)
			
			val emitJob = launch { 
				currentBlocksState.collect { blocks ->
					pageBlocksCache[chapterId]?.get(pageIndex)?.let {
						pageBlocksCache[chapterId]?.put(pageIndex, blocks)
					}
					send(blocks)
				} 
			}

			val flowJob = launch {
				getTranslationFlow(chapterId).collect { update ->
					currentBlocksState.update { blocks ->
						blocks.map { block ->
							update.updates[block.id]?.let { 
								if (update.state.ordinal >= block.state.ordinal) {
									if (update.state == TranslationState.REFINED) {
										mtCache[chapterId]?.remove(block.id)
									}
									block.copy(translatedText = it, state = update.state)
								} else {
									block
								}
							} ?: block
						}
					}
				}
			}

			// Fast Layer: Instant ML Kit Offline or Google API Online MT
			launch(Dispatchers.IO) {
				try {
					val allRefined = currentBlocksState.value.all { it.state == TranslationState.REFINED }
					if (allRefined) {
						return@launch
					}
					
					if (settings.isAiTranslateOffline) {
						val sourceCode = getMlKitLanguageCode(if (settings.aiSourceLang == "AUTO") ocrResult.language else settings.aiSourceLang)
						val targetCode = getMlKitLanguageCode(settings.aiTargetLang)
						val options = com.google.mlkit.nl.translate.TranslatorOptions.Builder()
							.setSourceLanguage(sourceCode)
							.setTargetLanguage(targetCode)
							.build()
						val mlKitTranslator = com.google.mlkit.nl.translate.Translation.getClient(options)
						mlKitTranslator.downloadModelIfNeeded().await()
						
						val originalTexts = currentBlocksState.value.map { it.originalText }
						val translatedList = mutableListOf<String>()
						for (text in originalTexts) {
							translatedList.add(mlKitTranslator.translate(text).await())
						}
						mlKitTranslator.close()
						
						currentBlocksState.update { blocks ->
							blocks.mapIndexed { index, block ->
								if (block.state == TranslationState.REFINED || block.state == TranslationState.MT) block else {
									val mtText = translatedList.getOrNull(index) ?: ""
									mtCache.getOrPut(chapterId) { ConcurrentHashMap() }[block.id] = mtText
									block.copy(translatedText = mtText, state = TranslationState.MT)
								}
							}
						}
					} else {
						val targetLangStr = settings.aiTargetLang
						val targetLang = getGoogleLanguageCode(targetLangStr)
						
						val originalTexts = currentBlocksState.value.map { it.originalText }
						val delimiter = "\n\n\n|||\n\n\n"
						val combinedText = originalTexts.joinToString(delimiter)
						val translatedCombined = runCatching { googleTranslator.translateCatching(combinedText, targetLang).getOrThrow().translatedText }.getOrElse { "" }
						val translatedList = translatedCombined.split(Regex("\\s*\\|\\s*\\|\\s*\\|\\s*")).map { it.trim() }
						
						val finalTranslations = if (translatedList.size == originalTexts.size) {
							translatedList
						} else {
							coroutineScope {
								originalTexts.map { text ->
									async {
										runCatching { googleTranslator.translateCatching(text, targetLang).getOrThrow().translatedText }.getOrElse { text }
									}
								}.awaitAll()
							}
						}
						
						currentBlocksState.update { blocks ->
							blocks.mapIndexed { index, block ->
								if (block.state == TranslationState.REFINED || block.state == TranslationState.MT) block else {
									val mtText = finalTranslations.getOrNull(index) ?: ""
									mtCache.getOrPut(chapterId) { ConcurrentHashMap() }[block.id] = mtText
									block.copy(translatedText = mtText, state = TranslationState.MT)
								}
							}
						}
					}
	
					translatePageDialoguesAtOnce(chapterId, pageIndex, currentBlocksState.value)
				} catch (e: Exception) {
					android.util.Log.e("MangaTranslator", "MT failed for page $pageIndex", e)
					// Keep pipeline moving: use OCR text as draft so LLM refinement can still run.
					currentBlocksState.update { blocks ->
						blocks.map { block ->
							if (block.state == TranslationState.REFINED || block.state == TranslationState.MT) block else {
								val mtText = block.originalText
								mtCache.getOrPut(chapterId) { ConcurrentHashMap() }[block.id] = mtText
								block.copy(
									translatedText = mtText,
									state = TranslationState.MT,
								)
							}
						}
					}
					translatePageDialoguesAtOnce(chapterId, pageIndex, currentBlocksState.value)
				}
			}
			
			awaitClose { 
				emitJob.cancel()
				flowJob.cancel() 
			}
		} catch (e: Throwable) {
			android.util.Log.e("MangaTranslator", "Fatal error in translatePage flow for page $pageIndex", e)
			throw e
		}
	}.flowOn(Dispatchers.Default)

	suspend fun translateChapter(chapterId: Long, uris: List<Uri>) = withContext(Dispatchers.Default) {
		val allDialogues = mutableListOf<DialogueBlock>()
		val chapterMap = chapterCache.getOrPut(chapterId) { ConcurrentHashMap() }

		// Parallel OCR pass
		uris.forEachIndexed { pIndex, uri ->
			val bitmap = decodeUriToBitmap(uri) ?: return@forEachIndexed
			val processedForOcr = preprocessBitmapForOcr(bitmap, OCR_SCALE_FACTOR)
			val image = InputImage.fromBitmap(processedForOcr, 0)
			val result = ocrProvider.runEnsembleOcr(image)
			processedForOcr.recycle()
			
			// Scale bounding boxes back down to original image dimensions
			val scaledBlocks = result.blocks.map { block ->
				block.copy(
					boundingBox = Rect(
						(block.boundingBox.left / OCR_SCALE_FACTOR).toInt(),
						(block.boundingBox.top / OCR_SCALE_FACTOR).toInt(),
						(block.boundingBox.right / OCR_SCALE_FACTOR).toInt(),
						(block.boundingBox.bottom / OCR_SCALE_FACTOR).toInt()
					)
				)
			}
			
			val bubbles = mergeBlocksIntoBubbles(scaledBlocks)
			
			bubbles.forEachIndexed { bIndex, bubble ->
				allDialogues.add(
					DialogueBlock(
						id = "p${pIndex}_b${bIndex}",
						original = bubble.text,
						mt_draft = bubble.text,
					),
				)
			}
			bitmap.recycle()
		}

		if (allDialogues.isEmpty()) return@withContext

		// Run Fast MT Pass to populate mt_draft
		try {
			if (settings.isAiTranslateOffline) {
				val sourceCode = getMlKitLanguageCode(settings.aiSourceLang)
				val targetCode = getMlKitLanguageCode(settings.aiTargetLang)
				val options = TranslatorOptions.Builder()
					.setSourceLanguage(sourceCode)
					.setTargetLanguage(targetCode)
					.build()
				val mlKitTranslator = Translation.getClient(options)
				mlKitTranslator.downloadModelIfNeeded().await()
				
				for (i in allDialogues.indices) {
					val draft = mlKitTranslator.translate(allDialogues[i].original).await()
					allDialogues[i] = allDialogues[i].copy(mt_draft = draft)
					val id = allDialogues[i].id
					mtCache.getOrPut(chapterId) { ConcurrentHashMap() }[id] = draft
				}
				mlKitTranslator.close()
			} else {
				val targetLangStr = settings.aiTargetLang
				val targetLang = getGoogleLanguageCode(targetLangStr)
				
				val originalTexts = allDialogues.map { it.original }
				val delimiter = "\n\n\n|||\n\n\n"
				val combinedText = originalTexts.joinToString(delimiter)
				val translatedCombined = runCatching { googleTranslator.translateCatching(combinedText, targetLang).getOrThrow().translatedText }.getOrElse { "" }
				val translatedList = translatedCombined.split(Regex("\\s*\\|\\s*\\|\\s*\\|\\s*")).map { it.trim() }
				
				val finalTranslations = if (translatedList.size == allDialogues.size) {
					translatedList
				} else {
					coroutineScope {
						originalTexts.map { text ->
							async {
								runCatching { googleTranslator.translateCatching(text, targetLang).getOrThrow().translatedText }.getOrElse { text }
							}
						}.awaitAll()
					}
				}
				for (i in allDialogues.indices) {
					allDialogues[i] = allDialogues[i].copy(mt_draft = finalTranslations[i])
					val id = allDialogues[i].id
					mtCache.getOrPut(chapterId) { ConcurrentHashMap() }[id] = finalTranslations[i]
				}
			}
			
			// Emit intermediate MT drafts immediately so the UI gets instantaneous translations!
			val mtUpdates = allDialogues.associate { it.id to it.mt_draft }
			getMutableTranslationFlow(chapterId).emit(TranslationUpdate(mtUpdates, TranslationState.MT))
		} catch (e: Exception) {
			android.util.Log.e("MangaTranslator", "Preload MT draft failed, falling back to OCR text", e)
		}

		// Split all dialogues into chunks of 12 for fast, robust sequential completion
		val chunks = allDialogues.chunked(12)
		chunks.forEachIndexed { chunkIndex, chunk ->
			try {
				val request = ChapterTranslationRequest(
					source_lang = settings.aiSourceLang,
					target_lang = settings.aiTargetLang,
					dialogues = chunk
				)

				val prompt = """
					<instructions>
					You are an elite, award-winning manga localizer. Your task is to translate an entire manga chapter from ${settings.aiSourceLang} to ${settings.aiTargetLang}.
					
					1. You MUST read the <context> carefully. Preserve character personalities, genders, and consistent naming.
					2. CJK languages often split sentences across multiple bubbles. You must intelligently reassemble fragmented sentences across the provided IDs to ensure natural, idiomatic flow in ${settings.aiTargetLang}.
					3. For SFX (onomatopoeia), translate them into descriptive action words in italics (e.g., *thump*, *whoosh*).
					4. You must output ONLY a valid JSON object. Do not include markdown blocks or extra text outside the JSON.
					5. To ensure maximum quality, first write your translation strategy and contextual analysis in the "reasoning" key, THEN provide the final translations in the "translations" array.
					
					JSON Schema constraint:
					{
					  "reasoning": "Step-by-step logic on how to handle fragmented sentences, tone, and context for this chapter.",
					  "translations": [
					    { "id": "p0_b0", "translated": "..." }
					  ]
					}
					</instructions>
					
					<context>
					${storyBrain.getNarrativeContext()}
					</context>
					
					<input>
					${json.encodeToString(request)}
					</input>
				""".trimIndent()

				val completion = aiRepository.getCompletion(
					listOf(
						ChatMessage("system", "You are an expert Localization AI operating at the highest professional tier. You process JSON strictly according to the provided schema."),
						ChatMessage("user", prompt)
					),
					useJsonMode = true
				).getOrThrow()

				val response = json.decodeFromString<ChapterTranslationResponse>(completion)
				val updates = mutableMapOf<String, String>()
				response.translations.forEach { 
					chapterMap[it.id] = it.translated 
					updates[it.id] = it.translated
					mtCache[chapterId]?.remove(it.id)
				}
				getMutableTranslationFlow(chapterId).emit(TranslationUpdate(updates, TranslationState.REFINED))
				android.util.Log.d("MangaTranslator", "Preload chunk ${chunkIndex + 1}/${chunks.size} completed successfully")
				
				// Respect 40 RPM: enforce a 1.5-second pacing delay between preload chunks
				if (chunkIndex < chunks.lastIndex) {
					delay(1500L)
				}
			} catch (e: Exception) {
				android.util.Log.e("MangaTranslator", "Preload chunk ${chunkIndex + 1}/${chunks.size} refinement failed", e)
			}
		}
	}

	private suspend fun translatePageDialoguesAtOnce(
		chapterId: Long,
		pageIndex: Int,
		blocks: List<TranslatedBlock>
	) {
		val chapterMap = chapterCache.getOrPut(chapterId) { ConcurrentHashMap() }
		val unrefinedBlocks = blocks.filter { block ->
			block.state != TranslationState.REFINED &&
			block.originalText.isNotBlank() &&
			!chapterMap.containsKey(block.id)
		}
		
		if (unrefinedBlocks.isEmpty()) return

		try {
			val delimiter = " ||| "
			val sourceTexts = unrefinedBlocks.map { it.originalText }
			val promptText = sourceTexts.joinToString(delimiter)

			val systemPrompt = """
				You are an elite, award-winning manga localizer.
				Translate the following sequential dialogue blocks from ${settings.aiSourceLang} to ${settings.aiTargetLang}.
				
				Format Constraint:
				1. The input blocks are separated by "$delimiter".
				2. You MUST return the translated blocks in the exact same order, separated by the exact same delimiter "$delimiter".
				3. Do not include block numbers, preamble, introduction, markdown blocks, or conversational filler. Output ONLY the translated blocks separated by "$delimiter".
			""".trimIndent()

			val completion = aiRepository.getCompletion(
				listOf(
					ChatMessage("system", systemPrompt),
					ChatMessage("user", promptText)
				),
				useJsonMode = false
			).getOrThrow()

			val translatedTexts = completion.split(delimiter)
				.map { it.trim() }
				.filter { it.isNotEmpty() }

			val updates = mutableMapOf<String, String>()
			
			if (translatedTexts.size == unrefinedBlocks.size) {
				unrefinedBlocks.forEachIndexed { index, block ->
					val translated = translatedTexts[index]
					chapterMap[block.id] = translated
					refinedTextCache[buildRefinedCacheKey(block.originalText, block.translatedText)] = translated
					updates[block.id] = translated
					mtCache[chapterId]?.remove(block.id)
				}
			} else {
				val lineTexts = completion.split("\n")
					.map { it.trim() }
					.filter { it.isNotEmpty() && !it.contains("|||") }
				if (lineTexts.size == unrefinedBlocks.size) {
					unrefinedBlocks.forEachIndexed { index, block ->
						val translated = lineTexts[index]
						chapterMap[block.id] = translated
						refinedTextCache[buildRefinedCacheKey(block.originalText, block.translatedText)] = translated
						updates[block.id] = translated
						mtCache[chapterId]?.remove(block.id)
					}
				} else {
					unrefinedBlocks.forEach { block ->
						val translated = runCatching {
							val singlePrompt = "Translate this single manga bubble to ${settings.aiTargetLang}: ${block.originalText}"
							aiRepository.getCompletion(
								listOf(ChatMessage("user", singlePrompt)),
								useJsonMode = false
							).getOrThrow().trim()
						}.getOrElse { block.translatedText.ifBlank { block.originalText } }
						
						chapterMap[block.id] = translated
						refinedTextCache[buildRefinedCacheKey(block.originalText, block.translatedText)] = translated
						updates[block.id] = translated
						mtCache[chapterId]?.remove(block.id)
					}
				}
			}

			if (updates.isNotEmpty()) {
				getMutableTranslationFlow(chapterId).emit(
					TranslationUpdate(updates, TranslationState.REFINED)
				)
			}
		} catch (e: Exception) {
			android.util.Log.e("MangaTranslator", "Page $pageIndex LLM refinement failed", e)
		}
	}

	private fun buildRefinedCacheKey(original: String, draft: String): String {
		return "${settings.aiSourceLang}|${settings.aiTargetLang}|${settings.aiModel}|${original.trim()}|${draft.trim()}"
	}

	// --- Heuristics and Utilities ---

	private fun preprocessBitmapForOcr(original: Bitmap, scaleFactor: Float): Bitmap {
		// Hardware bitmaps cannot be drawn on a software canvas. We must copy it to a software bitmap first.
		// Bitmap.Config.HARDWARE only exists from API 26; older devices never produce hardware bitmaps.
		val softwareBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && original.config == Bitmap.Config.HARDWARE) {
			original.copy(Bitmap.Config.ARGB_8888, false) ?: original
		} else {
			original
		}

		val targetWidth = (softwareBitmap.width * scaleFactor).toInt()
		val targetHeight = (softwareBitmap.height * scaleFactor).toInt()
		val scaledBitmap = Bitmap.createScaledBitmap(softwareBitmap, targetWidth, targetHeight, true)

		val processedBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
		val canvas = android.graphics.Canvas(processedBitmap)
		val paint = android.graphics.Paint()
		
		val colorMatrix = android.graphics.ColorMatrix().apply {
			setSaturation(0f) // Convert to grayscale
			
			// Increase contrast drastically to make text pop against screentones/backgrounds
			val scale = 1.8f
			val translate = (-.5f * scale + .5f) * 255f
			val contrastMatrix = android.graphics.ColorMatrix(floatArrayOf(
				scale, 0f, 0f, 0f, translate,
				0f, scale, 0f, 0f, translate,
				0f, 0f, scale, 0f, translate,
				0f, 0f, 0f, 1f, 0f
			))
			postConcat(contrastMatrix)
		}
		
		paint.colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
		canvas.drawBitmap(scaledBitmap, 0f, 0f, paint)
		
		if (softwareBitmap != original) {
			softwareBitmap.recycle()
		}
		scaledBitmap.recycle()
		
		return processedBitmap
	}

	private fun mergeBlocksIntoBubbles(blocks: List<OcrProvider.MangaBlock>): List<MangaBubble> {
		val clustered = mutableListOf<MangaBubble>()
		val used = mutableSetOf<Int>()
		val sorted = blocks.indices.sortedWith(compareBy({ blocks[it].boundingBox.top }, { blocks[it].boundingBox.left }))

		for (i in sorted) {
			if (i in used) continue
			val cluster = mutableListOf(blocks[i]); used.add(i)
			var changed: Boolean
			do {
				changed = false
				for (j in sorted) {
					if (j in used) continue
					if (cluster.any { isClose(it.boundingBox, blocks[j].boundingBox) }) {
						cluster.add(blocks[j]); used.add(j); changed = true
					}
				}
			} while (changed)
			
			val mergedText = cluster.sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
				.joinToString(" ") { it.text }
			val mergedBox = cluster.map { it.boundingBox }.let { boxes ->
				Rect(boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
			}
			clustered.add(MangaBubble(mergedText, mergedBox))
		}
		return clustered
	}

	private fun mergeNearbyBubbles(bubbles: List<MangaBubble>): List<MangaBubble> {
		if (bubbles.size < 2) return bubbles

		val merged = mutableListOf<MangaBubble>()
		val used = BooleanArray(bubbles.size)
		val sorted = bubbles.indices.sortedWith(compareBy({ bubbles[it].box.top }, { bubbles[it].box.left }))

		for (i in sorted) {
			if (used[i]) continue
			val cluster = mutableListOf(bubbles[i])
			used[i] = true
			var changed: Boolean
			do {
				changed = false
				for (j in sorted) {
					if (used[j]) continue
					if (cluster.any { isBubbleClose(it.box, bubbles[j].box) }) {
						cluster.add(bubbles[j])
						used[j] = true
						changed = true
					}
				}
			} while (changed)

			val mergedText = cluster.sortedWith(compareBy({ it.box.top }, { it.box.left }))
				.joinToString(" ") { it.text }
			val mergedBox = cluster.map { it.box }.let { boxes ->
				Rect(boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
			}
			merged.add(MangaBubble(mergedText, mergedBox))
		}
		return merged
	}

	private fun isBubbleClose(a: Rect, b: Rect): Boolean {
		val avgW = (a.width() + b.width()) / 2f
		val avgH = (a.height() + b.height()) / 2f
		val centerDistX = kotlin.math.abs(a.centerX() - b.centerX())
		val centerDistY = kotlin.math.abs(a.centerY() - b.centerY())

		val nearByCenter = centerDistX <= avgW * 1.45f && centerDistY <= avgH * 1.45f
		return isClose(a, b) || nearByCenter
	}

	private fun isClose(a: Rect, b: Rect): Boolean {
		val hOverlap = Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left))
		val vOverlap = Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top))
		val vGap = if (a.bottom < b.top) b.top - a.bottom else if (b.bottom < a.top) a.top - b.bottom else 0
		val hGap = if (a.right < b.left) b.left - a.right else if (b.right < a.left) a.left - b.right else 0
		val minW = minOf(a.width(), b.width()).toFloat()
		val minH = minOf(a.height(), b.height()).toFloat()
		val avgW = (a.width() + b.width()) / 2f
		val avgH = (a.height() + b.height()) / 2f
		val verticalNeighbor = vGap <= maxOf(25f, avgH * 0.35f) && hOverlap >= minW * 0.3f
		val horizontalNeighbor = hGap <= maxOf(25f, avgW * 0.35f) && vOverlap >= minH * 0.3f
		return verticalNeighbor || horizontalNeighbor || Rect.intersects(a, b)
	}

	private fun sampleBackgroundColor(bitmap: Bitmap, box: Rect): Int {
		return try {
			val samplePoints = listOf(Pair(box.left + 2, box.top + 2), Pair(box.right - 2, box.top + 2), Pair(box.left + 2, box.bottom - 2), Pair(box.right - 2, box.bottom - 2))
			val colors = samplePoints.mapNotNull { (x, y) -> if (x in 0 until bitmap.width && y in 0 until bitmap.height) bitmap.getPixel(x, y) else null }
			if (colors.isEmpty()) return Color.WHITE
			colors.maxByOrNull { Color.red(it) + Color.green(it) + Color.blue(it) } ?: Color.WHITE
		} catch (e: Exception) { Color.WHITE }
	}

	private suspend fun decodeUriToBitmap(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
		try {
			if (uri.scheme == "file") com.nyora.hasan72341.core.image.BitmapDecoderCompat.decode(File(uri.path!!)) else null
		} catch (e: Exception) { null }
	}

	private fun getGoogleLanguageCode(language: String): Language {
		return when (language.lowercase()) {
			"english" -> Language.ENGLISH
			"spanish" -> Language.SPANISH
			"french" -> Language.FRENCH
			"german" -> Language.GERMAN
			"chinese" -> Language.CHINESE_SIMPLIFIED
			"japanese" -> Language.JAPANESE
			"korean" -> Language.KOREAN
			"portuguese" -> Language.PORTUGUESE
			"italian" -> Language.ITALIAN
			"russian" -> Language.RUSSIAN
			"arabic" -> Language.ARABIC
			"hindi" -> Language.HINDI
			"bengali" -> Language.BENGALI
			"turkish" -> Language.TURKISH
			"vietnamese" -> Language.VIETNAMESE
			"polish" -> Language.POLISH
			"dutch" -> Language.DUTCH
			"thai" -> Language.THAI
			"indonesian" -> Language.INDONESIAN
			"greek" -> Language.GREEK
			else -> Language.ENGLISH
		}
	}

	private fun getMlKitLanguageCode(lang: String): String {
		return when (lang.lowercase()) {
			"ja", "japanese" -> TranslateLanguage.JAPANESE
			"zh", "chinese" -> TranslateLanguage.CHINESE
			"ko", "korean" -> TranslateLanguage.KOREAN
			"en", "english" -> TranslateLanguage.ENGLISH
			"es", "spanish" -> TranslateLanguage.SPANISH
			"fr", "french" -> TranslateLanguage.FRENCH
			"de", "german" -> TranslateLanguage.GERMAN
			"pt", "portuguese" -> TranslateLanguage.PORTUGUESE
			"it", "italian" -> TranslateLanguage.ITALIAN
			"ru", "russian" -> TranslateLanguage.RUSSIAN
			"ar", "arabic" -> TranslateLanguage.ARABIC
			"hi", "hindi" -> TranslateLanguage.HINDI
			"bn", "bengali" -> TranslateLanguage.BENGALI
			"tr", "turkish" -> TranslateLanguage.TURKISH
			"vi", "vietnamese" -> TranslateLanguage.VIETNAMESE
			"pl", "polish" -> TranslateLanguage.POLISH
			"nl", "dutch" -> TranslateLanguage.DUTCH
			"th", "thai" -> TranslateLanguage.THAI
			"id", "indonesian" -> TranslateLanguage.INDONESIAN
			"el", "greek" -> TranslateLanguage.GREEK
			else -> TranslateLanguage.ENGLISH
		}
	}

	private data class MangaBubble(val text: String, val box: Rect)
}
