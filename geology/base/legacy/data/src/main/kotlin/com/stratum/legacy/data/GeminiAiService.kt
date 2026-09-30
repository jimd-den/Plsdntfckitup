package com.stratum.legacy.data

import android.graphics.BitmapFactory
import android.graphics.Color
import com.stratum.legacy.domain.AiGameMechanicGeneratorService
import com.stratum.legacy.domain.AiModelItem
import com.stratum.legacy.domain.BaseWeaponType
import com.stratum.legacy.domain.CharacterAnimationState
import com.stratum.legacy.domain.CustomGameMechanic
import com.stratum.legacy.domain.DamageType
import com.stratum.legacy.domain.MechanicTriggerType
import com.stratum.legacy.domain.NsibidiRune
import com.stratum.legacy.domain.OpenRouterConfig
import com.stratum.legacy.domain.PnpCharacterClass
import com.stratum.legacy.domain.PnpRulebook
import com.stratum.legacy.domain.PnpRulebookFactory
import com.stratum.legacy.domain.PnpSkillCheck
import com.stratum.legacy.domain.SpriteAnimationTrack
import com.stratum.legacy.domain.SpritePoliceReport
import com.stratum.legacy.domain.SpriteSheetData
import com.stratum.legacy.domain.WeaponAffix
import com.stratum.legacy.domain.WeaponItem
import com.stratum.legacy.domain.WeaponRarity
import com.stratum.legacy.domain.WeaponStatRandomizer
import com.stratum.legacy.domain.GameThemeProfile
import com.stratum.legacy.domain.DiabloEra
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * CHAPTER 10: DATA AI SERVICE & OPEN API GENERATOR
 *
 * Implements:
 * 1. OpenRouter & OpenAI-compatible REST integration:
 *    - Dynamically lists latest available FREE models first, and PAID models second.
 *    - Custom endpoint & base URL compatibility.
 * 2. Google Gemini 2.5/3.5 Flash fallback via BuildConfig.GEMINI_API_KEY.
 * 3. AI Sprite Generator synthesizing animated 4x4 sprite sheets transparently policed for alpha transparency.
 * 4. Action RPG weapon affix generator, P&P rulebook designer, and combat mechanic inventor.
 * 5. Robust offline procedural synthesis engine infused with ancient Igbo history.
 */

@JsonClass(generateAdapter = true)
data class OpenRouterPricing(
    @Json(name = "prompt") val prompt: String? = null,
    @Json(name = "completion") val completion: String? = null,
    @Json(name = "image") val image: String? = null
)

@JsonClass(generateAdapter = true)
data class OpenRouterArchitecture(
    @Json(name = "modality") val modality: String? = null,
    @Json(name = "input_modalities") val input_modalities: List<String>? = null,
    @Json(name = "output_modalities") val output_modalities: List<String>? = null
)

@JsonClass(generateAdapter = true)
data class OpenRouterModelItem(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "pricing") val pricing: OpenRouterPricing? = null,
    @Json(name = "architecture") val architecture: OpenRouterArchitecture? = null
)

@JsonClass(generateAdapter = true)
data class OpenRouterModelsResponse(
    @Json(name = "data") val data: List<OpenRouterModelItem>? = null
)

@JsonClass(generateAdapter = true)
data class GeminiContentPart(
    @Json(name = "text") val text: String? = null
)

@JsonClass(generateAdapter = true)
data class GeminiContent(
    @Json(name = "parts") val parts: List<GeminiContentPart>
)

@JsonClass(generateAdapter = true)
data class GeminiApiRequest(
    @Json(name = "contents") val contents: List<GeminiContent>
)

@JsonClass(generateAdapter = true)
data class GeminiCandidate(
    @Json(name = "content") val content: GeminiContent?
)

@JsonClass(generateAdapter = true)
data class GeminiApiResponse(
    @Json(name = "candidates") val candidates: List<GeminiCandidate>?
)

@JsonClass(generateAdapter = true)
data class OpenAiChatMessage(
    @Json(name = "role") val role: String,
    @Json(name = "content") val content: String
)

@JsonClass(generateAdapter = true)
data class OpenAiChatRequest(
    @Json(name = "model") val model: String,
    @Json(name = "messages") val messages: List<OpenAiChatMessage>,
    @Json(name = "temperature") val temperature: Double = 0.7
)

@JsonClass(generateAdapter = true)
data class OpenAiChoice(
    @Json(name = "message") val message: OpenAiChatMessage?
)

@JsonClass(generateAdapter = true)
data class OpenAiChatResponse(
    @Json(name = "choices") val choices: List<OpenAiChoice>?
)

@JsonClass(generateAdapter = true)
data class OpenAiImageGenerationRequest(
    @Json(name = "prompt") val prompt: String,
    @Json(name = "model") val model: String? = null,
    @Json(name = "n") val n: Int = 1,
    @Json(name = "size") val size: String = "512x512",
    @Json(name = "response_format") val response_format: String? = "url"
)

@JsonClass(generateAdapter = true)
data class OpenAiImageData(
    @Json(name = "url") val url: String? = null,
    @Json(name = "b64_json") val b64_json: String? = null
)

@JsonClass(generateAdapter = true)
data class OpenAiImageGenerationResponse(
    @Json(name = "data") val data: List<OpenAiImageData>? = null
)

interface GeminiRestApi {
    @POST("v1beta/models/gemini-2.5-flash:generateContent")
    suspend fun generateContent(
        @Query("key") apiKey: String,
        @Body request: GeminiApiRequest
    ): GeminiApiResponse
}

class GeminiAiServiceImpl(
    /**
     * Supplied by the composition root rather than read from BuildConfig, so
     * this module does not depend on the application it happens to ship in.
     */
    private val geminiApiKey: String = "",
) : AiGameMechanicGeneratorService {

    private var currentConfig = OpenRouterConfig()

    override fun setOpenRouterConfig(config: OpenRouterConfig) {
        currentConfig = config
    }

    override fun getOpenRouterConfig(): OpenRouterConfig {
        return currentConfig
    }

    override suspend fun fetchAvailableModels(baseUrl: String, apiKey: String): Result<List<AiModelItem>> = withContext(Dispatchers.IO) {
        val cleanBaseUrl = (if (baseUrl.isNotBlank()) baseUrl else currentConfig.baseUrl).trimEnd('/')
        val endpoint = "$cleanBaseUrl/models"
        val effectiveKey = if (apiKey.isNotBlank()) apiKey else currentConfig.apiKey

        try {
            val requestBuilder = Request.Builder().url(endpoint)
            if (effectiveKey.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $effectiveKey")
            }
            requestBuilder.addHeader("HTTP-Referer", "https://ai.studio/build")
            requestBuilder.addHeader("X-Title", "Igbo ARPG Engine")

            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            if (response.isSuccessful) {
                val bodyString = response.body?.string() ?: ""
                val adapter = moshi.adapter(OpenRouterModelsResponse::class.java)
                val parsed = adapter.fromJson(bodyString)
                val list = parsed?.data

                if (!list.isNullOrEmpty()) {
                    val mapped = list.map { item ->
                        val isFree = item.id.endsWith(":free", ignoreCase = true) ||
                                item.pricing?.prompt == "0" ||
                                item.pricing?.prompt == "0.0" ||
                                (item.name ?: "").contains("(free)", ignoreCase = true)

                        val outputModalities = item.architecture?.output_modalities ?: emptyList()
                        val inputModalities = item.architecture?.input_modalities ?: emptyList()
                        val modality = item.architecture?.modality ?: ""

                        val isExplicitImageGen = item.id.contains("flux", ignoreCase = true) ||
                                item.id.contains("dall-e", ignoreCase = true) ||
                                item.id.contains("stable-diffusion", ignoreCase = true) ||
                                item.id.contains("sdxl", ignoreCase = true) ||
                                item.id.contains("recraft", ignoreCase = true) ||
                                item.id.contains("imagen", ignoreCase = true) ||
                                item.id.contains("midjourney", ignoreCase = true) ||
                                item.id.contains("ideogram", ignoreCase = true) ||
                                item.id.contains("black-forest-labs", ignoreCase = true) ||
                                item.id.contains("playground", ignoreCase = true) ||
                                item.id.contains("cogview", ignoreCase = true)

                        // Strict image generation capability: model MUST output images, not just take them as input
                        val isImageOutput = outputModalities.any { it.equals("image", ignoreCase = true) } ||
                                modality.contains("->image", ignoreCase = true) ||
                                (isExplicitImageGen && !item.id.contains("prompt", ignoreCase = true))

                        // Vision input only (reads images, outputs text)
                        val isVisionInputOnly = !isImageOutput && (
                                inputModalities.any { it.equals("image", ignoreCase = true) } ||
                                item.id.contains("vision", ignoreCase = true) ||
                                item.id.contains("-vl", ignoreCase = true) ||
                                item.id.contains("vl-", ignoreCase = true)
                        )

                        val priceStr = if (isFree) "Free (0.00)" else "Paid Usage"
                        val displayName = item.name ?: item.id.substringAfter("/")

                        AiModelItem(
                            id = item.id,
                            name = displayName,
                            description = item.description?.take(100) ?: "",
                            isFree = isFree,
                            isImageCapable = isImageOutput,
                            isVisionInput = isVisionInputOnly,
                            pricingDisplay = priceStr,
                            outputModalities = if (isImageOutput) listOf("image") else listOf("text")
                        )
                    }

                    // Sort: FREE models listed first (with image-output models prioritized), PAID models second
                    val sorted = mapped.sortedWith(
                        compareByDescending<AiModelItem> { it.isFree }
                            .thenByDescending { it.isImageCapable }
                            .thenBy { it.name }
                    )
                    return@withContext Result.success(sorted)
                }
            }
        } catch (e: Exception) {
            // Fallback to offline prioritized model catalog
        }

        // Fallback dynamic catalog: correctly separating image output from text/vision models
        val fallbackList = listOf(
            // FREE MODELS (LISTED FIRST)
            AiModelItem(
                id = "google/gemini-2.0-flash-exp:free",
                name = "Google: Gemini 2.0 Flash Exp (Free)",
                description = "High-speed multimodal LLM for game mechanics, weapon lore, and rules.",
                isFree = true,
                isImageCapable = false,
                isVisionInput = true,
                pricingDisplay = "Free (0.00)",
                outputModalities = listOf("text")
            ),
            AiModelItem(
                id = "black-forest-labs/flux-1-schnell:free",
                name = "FLUX.1 Schnell (Free Image Gen)",
                description = "Dedicated 12B pixel & sprite image generation model with free tier.",
                isFree = true,
                isImageCapable = true,
                isVisionInput = false,
                pricingDisplay = "Free (0.00)",
                outputModalities = listOf("image")
            ),
            AiModelItem(
                id = "qwen/qwen-2.5-vl-72b-instruct:free",
                name = "Qwen: 2.5 Vision 72B Instruct (Free)",
                description = "Vision-language model for sprite analysis and art inspection (text output).",
                isFree = true,
                isImageCapable = false,
                isVisionInput = true,
                pricingDisplay = "Free (0.00)",
                outputModalities = listOf("text")
            ),
            AiModelItem(
                id = "meta-llama/llama-3.3-70b-instruct:free",
                name = "Meta: Llama 3.3 70B Instruct (Free)",
                description = "State-of-the-art open weights model for deep ARPG mechanics and lore.",
                isFree = true,
                isImageCapable = false,
                isVisionInput = false,
                pricingDisplay = "Free (0.00)",
                outputModalities = listOf("text")
            ),
            AiModelItem(
                id = "mistralai/mistral-7b-instruct:free",
                name = "Mistral: 7B Instruct (Free)",
                description = "Ultra-fast low latency generation for real-time weapon affixes and game rules.",
                isFree = true,
                isImageCapable = false,
                isVisionInput = false,
                pricingDisplay = "Free (0.00)",
                outputModalities = listOf("text")
            ),
            AiModelItem(
                id = "deepseek/deepseek-r1:free",
                name = "DeepSeek: R1 Reasoning (Free)",
                description = "Deep mathematical reasoning for balancing weapon DPS and probability curves.",
                isFree = true,
                isImageCapable = false,
                isVisionInput = false,
                pricingDisplay = "Free (0.00)",
                outputModalities = listOf("text")
            ),

            // PAID MODELS (LISTED SECOND)
            AiModelItem(
                id = "black-forest-labs/flux-1-schnell",
                name = "FLUX.1 Schnell (Dedicated Image/Sprite)",
                description = "High-speed 12B parameter image generation model specialized in game asset synthesis.",
                isFree = false,
                isImageCapable = true,
                isVisionInput = false,
                pricingDisplay = "$0.003/img",
                outputModalities = listOf("image")
            ),
            AiModelItem(
                id = "black-forest-labs/flux-1-dev",
                name = "FLUX.1 Dev (Ultra-High Detail Image)",
                description = "Professional visual generation model with supreme prompt adherence.",
                isFree = false,
                isImageCapable = true,
                isVisionInput = false,
                pricingDisplay = "$0.025/img",
                outputModalities = listOf("image")
            ),
            AiModelItem(
                id = "stabilityai/stable-diffusion-xl-base-1.0",
                name = "Stability AI: SDXL Base 1.0",
                description = "Industry standard diffusion model for 2D game sprites and pixel textures.",
                isFree = false,
                isImageCapable = true,
                isVisionInput = false,
                pricingDisplay = "$0.002/img",
                outputModalities = listOf("image")
            ),
            AiModelItem(
                id = "anthropic/claude-3.5-sonnet",
                name = "Anthropic: Claude 3.5 Sonnet",
                description = "Industry-leading reasoning and creative narrative architecture.",
                isFree = false,
                isImageCapable = false,
                isVisionInput = true,
                pricingDisplay = "$3.00/M tokens",
                outputModalities = listOf("text")
            ),
            AiModelItem(
                id = "openai/gpt-4o",
                name = "OpenAI: GPT-4o Omni Multimodal",
                description = "Flagship multimodal intelligence with image and vision comprehension.",
                isFree = false,
                isImageCapable = false,
                isVisionInput = true,
                pricingDisplay = "$2.50/M tokens",
                outputModalities = listOf("text")
            ),
            AiModelItem(
                id = "deepseek/deepseek-chat",
                name = "DeepSeek: V3 Chat",
                description = "Fast, efficient general purpose generation.",
                isFree = false,
                isImageCapable = false,
                isVisionInput = false,
                pricingDisplay = "$0.14/M tokens",
                outputModalities = listOf("text")
            )
        )

        Result.success(fallbackList)
    }

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    private val geminiRetrofit = Retrofit.Builder()
        .baseUrl("https://generativelanguage.googleapis.com/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    private val geminiApi: GeminiRestApi = geminiRetrofit.create(GeminiRestApi::class.java)

    /**
     * Unified AI query engine:
     * 1. Attempts OpenRouter / OpenAI-compatible endpoint with chosen model if key configured.
     * 2. Falls back to Gemini API if BuildConfig.GEMINI_API_KEY is available.
     * 3. Returns empty string to invoke smart procedural fallback.
     */
    private suspend fun queryAi(systemPrompt: String, userPrompt: String): String = withContext(Dispatchers.IO) {
        val openRouterKey = currentConfig.apiKey.trim()
        val hasOpenRouterKey = openRouterKey.isNotEmpty()

        if (hasOpenRouterKey) {
            try {
                val openAiAdapter = moshi.adapter(OpenAiChatRequest::class.java)
                val responseAdapter = moshi.adapter(OpenAiChatResponse::class.java)

                val reqObj = OpenAiChatRequest(
                    model = currentConfig.model.ifEmpty { "anthropic/claude-3.5-sonnet" },
                    messages = listOf(
                        OpenAiChatMessage(role = "system", content = systemPrompt),
                        OpenAiChatMessage(role = "user", content = userPrompt)
                    ),
                    temperature = 0.7
                )
                val jsonPayload = openAiAdapter.toJson(reqObj)
                val endpoint = "${currentConfig.baseUrl.trimEnd('/')}/chat/completions"

                val httpRequest = Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer $openRouterKey")
                    .addHeader("HTTP-Referer", "https://ai.studio/build")
                    .addHeader("X-Title", "Igbo ARPG Engine")
                    .addHeader("Content-Type", "application/json")
                    .post(jsonPayload.toRequestBody("application/json".toMediaType()))
                    .build()

                val httpResponse = okHttpClient.newCall(httpRequest).execute()
                if (httpResponse.isSuccessful) {
                    val respString = httpResponse.body?.string() ?: ""
                    val parsed = responseAdapter.fromJson(respString)
                    val reply = parsed?.choices?.firstOrNull()?.message?.content
                    if (!reply.isNullOrEmpty()) {
                        return@withContext reply
                    }
                }
            } catch (e: Exception) {
                // Continue to Gemini fallback
            }
        }

        // Gemini Fallback
        val geminiKey = geminiApiKey.trim()
        val hasGeminiKey = geminiKey.isNotEmpty() && !geminiKey.contains("MY_GEMINI_API_KEY")
        if (hasGeminiKey) {
            try {
                val fullPrompt = "$systemPrompt\n\nPlayer Prompt: $userPrompt"
                val request = GeminiApiRequest(
                    contents = listOf(
                        GeminiContent(parts = listOf(GeminiContentPart(text = fullPrompt)))
                    )
                )
                val response = geminiApi.generateContent(geminiKey, request)
                val text = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                if (!text.isNullOrEmpty()) {
                    return@withContext text
                }
            } catch (e: Exception) {
                // Continue to procedural fallback
            }
        }

        return@withContext ""
    }

    override suspend fun generateWeaponFromPrompt(userPrompt: String): Result<WeaponItem> = withContext(Dispatchers.IO) {
        val systemPrompt = """
            You are a master game designer for an Action RPG set in an ancient Igbo mythological universe (Igbo-Ukwu roped bronzes, Nri divine royalty, Nsibidi ideograms, Amadioha thunder, Anyanwu sun fire).
            Return a structured description for this weapon with:
            NAME: [Name of weapon]
            BASE_TYPE: [OFO_SCEPTER, ALO_WAR_STAFF, MMA_NKWU, IKENGA_CLEAVER, ASA_JAVELIN, ULI_SACRED_BOW]
            RARITY: [RARE, LEGENDARY, ANCIENT_RELIC]
            DAMAGE: [MinDmg]-[MaxDmg]
            SPEED: [Attacks per second, e.g. 1.2]
            SPECIAL_MECHANIC: [Unique Action RPG game mechanic]
            LORE: [Historical/mythological lore connection to ancient Igbo civilization]
        """.trimIndent()

        val responseText = queryAi(systemPrompt, userPrompt)
        if (responseText.isNotEmpty()) {
            try {
                val parsed = parseAiWeaponResponse(responseText, userPrompt)
                return@withContext Result.success(parsed)
            } catch (e: Exception) {
                return@withContext Result.success(generateSmartFallbackWeapon(userPrompt))
            }
        } else {
            return@withContext Result.success(generateSmartFallbackWeapon(userPrompt))
        }
    }

    override suspend fun inventMechanicFromPrompt(userPrompt: String): Result<CustomGameMechanic> = withContext(Dispatchers.IO) {
        val systemPrompt = """
            Invent an innovative, exciting Action RPG combat loop mechanic inspired by ancient Igbo history (Igbo-Ukwu bronzeworking, Nsibidi secret script, Ikenga horned strength, Aro Ekpe stealth, or Amadioha thunder).
            Respond strictly in this format:
            NAME: [English Name]
            IGBO_NAME: [Igbo translation or term]
            DESCRIPTION: [Game mechanic rules explanation]
            TRIGGER: [ON_HIT, ON_CRIT, ON_TAKE_DAMAGE, ON_KILL]
            PROC_CHANCE: [e.g. 0.35]
        """.trimIndent()

        val responseText = queryAi(systemPrompt, userPrompt)
        if (responseText.isNotEmpty()) {
            try {
                val mechanic = parseAiMechanicResponse(responseText, userPrompt)
                return@withContext Result.success(mechanic)
            } catch (e: Exception) {
                return@withContext Result.success(generateSmartFallbackMechanic(userPrompt))
            }
        } else {
            return@withContext Result.success(generateSmartFallbackMechanic(userPrompt))
        }
    }

    override suspend fun generatePnpRulebookFromPrompt(userPrompt: String): Result<PnpRulebook> = withContext(Dispatchers.IO) {
        val systemPrompt = """
            Design a Pen and Paper RPG module inspired by ancient Igbo history.
            Provide title, setting notes, 2 character classes, and 2 D20 skill checks.
        """.trimIndent()

        val responseText = queryAi(systemPrompt, userPrompt)
        if (responseText.isNotEmpty()) {
            return@withContext Result.success(
                PnpRulebook(
                    title = "Chronicles: $userPrompt".take(40),
                    settingName = "Ancient Ala Igbo (Generated)",
                    authorNotes = "Generated via OpenRouter / AI Engine (${currentConfig.model})",
                    classes = PnpRulebookFactory.createDefaultIgboRulebook().classes,
                    skillChecks = PnpRulebookFactory.createDefaultIgboRulebook().skillChecks,
                    rawMarkdownNotes = responseText
                )
            )
        } else {
            return@withContext Result.success(generateSmartFallbackRulebook(userPrompt))
        }
    }

    override suspend fun generateSpriteFromPrompt(userPrompt: String): Result<SpriteSheetData> = withContext(Dispatchers.IO) {
        val systemPrompt = """
            You are a 2D pixel artist and game designer creating sprite characters for an ancient Igbo ARPG.
            Design a sprite character based on the prompt.
            Respond in this format:
            NAME: [Character Name]
            ROLE: [Igbo Theme Role, e.g., Ozo Bronze Warrior, Mmanwu Spirit, Dibia Shaman, Leopard Hunter, Ikenga Automaton]
            DESCRIPTION: [Visual and lore description]
            PRIMARY_COLOR: [Hex code like #D4AF37 for bronze gold, #FF9800 for solar fire]
            ACCENT_COLOR: [Hex code like #00E5FF for thunder cyan, #D84315 for terracotta, #FFFFFF for ivory]
            HEAD_FEATURE: [OZO_FEATHER, IKENGA_HORNS, MMANWU_MASK, BRONZE_CROWN]
        """.trimIndent()

        val responseText = queryAi(systemPrompt, userPrompt)
        var name = "AI: $userPrompt".take(30)
        var role = "Ancestral Igbo Warrior"
        var desc = "Created by AI model ${currentConfig.model} honoring ancient Igbo metallurgy."
        var primaryColor = 0xFFD4AF37.toInt() // Bronze Gold
        var accentColor = 0xFF00E5FF.toInt()  // Amadioha Cyan
        var feature = "OZO_FEATHER"

        if (responseText.isNotEmpty()) {
            responseText.lines().forEach { line ->
                val upper = line.uppercase()
                when {
                    upper.startsWith("NAME:") -> name = line.substringAfter(":").trim()
                    upper.startsWith("ROLE:") -> role = line.substringAfter(":").trim()
                    upper.startsWith("DESCRIPTION:") -> desc = line.substringAfter(":").trim()
                    upper.startsWith("PRIMARY_COLOR:") -> {
                        try {
                            val hex = line.substringAfter(":").trim()
                            primaryColor = Color.parseColor(if (hex.startsWith("#")) hex else "#$hex")
                        } catch (e: Exception) {}
                    }
                    upper.startsWith("ACCENT_COLOR:") -> {
                        try {
                            val hex = line.substringAfter(":").trim()
                            accentColor = Color.parseColor(if (hex.startsWith("#")) hex else "#$hex")
                        } catch (e: Exception) {}
                    }
                    upper.startsWith("HEAD_FEATURE:") -> {
                        feature = line.substringAfter(":").trim().uppercase()
                    }
                }
            }
        } else {
            // Procedural heuristic based on keywords
            when {
                userPrompt.contains("spirit", true) || userPrompt.contains("mask", true) -> {
                    name = "Mmanwu Ancestral Spirit: $userPrompt".take(30)
                    role = "Mmanwu Spirit"
                    desc = "Sacred masked spirit cloaked in woven raffia and Uli geometric symbols."
                    accentColor = 0xFFD84315.toInt() // Terracotta
                    feature = "MMANWU_MASK"
                }
                userPrompt.contains("horn", true) || userPrompt.contains("golem", true) || userPrompt.contains("ikenga", true) -> {
                    name = "Ikenga Bronze Automaton: $userPrompt".take(30)
                    role = "Ikenga Horned Golem"
                    desc = "Ancient horned bronze sentinel charged with sacred Amadioha lightning."
                    primaryColor = 0xFFCD7F32.toInt()
                    accentColor = 0xFF76FF03.toInt()
                    feature = "IKENGA_HORNS"
                }
                userPrompt.contains("shaman", true) || userPrompt.contains("priest", true) || userPrompt.contains("dibia", true) -> {
                    name = "Dibia Seer of Agwunsi: $userPrompt".take(30)
                    role = "Dibia Healer & Mystic"
                    desc = "Carries divining seeds, white chalk (Nzu), and bronze bells."
                    accentColor = 0xFFFFFFFF.toInt()
                    feature = "BRONZE_CROWN"
                }
                else -> {
                    name = "Dike Ozo Bronze Champion: $userPrompt".take(30)
                    role = "Ozo Bronze Warrior"
                    desc = "Adorned in lost-wax cast pectoral bronze armor and eagle feathers."
                    feature = "OZO_FEATHER"
                }
            }
        }

        val dummySheet = SpriteSheetData(
            name = name,
            description = desc,
            frameWidth = 48,
            frameHeight = 48,
            columns = 4,
            rows = 4,
            isCustomImport = true,
            igboThemeRole = role
        )

        // Synthesize the 4x4 animated sprite sheet bitmap
        val rawBitmap = SpriteFileTransferManager.generateProceduralSpriteSheetBitmap(
            sheet = dummySheet,
            primaryColor = primaryColor,
            accentColor = accentColor,
            feature = feature
        )

        // Transparently police the sprite sheet: enforce alpha transparency and clean 4x4 grid
        val (policedBitmap, policeReport) = SpriteFileTransferManager.policeSpriteBitmap(
            sourceBitmap = rawBitmap,
            targetCols = 4,
            targetRows = 4,
            autoChromaKey = true,
            tolerance = 0.15f
        )

        // Encode as Base64 PNG
        val baos = java.io.ByteArrayOutputStream()
        policedBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
        val base64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)

        val finalSheet = dummySheet.copy(
            rawImageUriOrBase64 = base64,
            frameWidth = policeReport.frameWidth,
            frameHeight = policeReport.frameHeight,
            columns = policeReport.detectedGridCols,
            rows = policeReport.detectedGridRows
        )
        Result.success(finalSheet)
    }

    override suspend fun generateRawImageSpriteSheet(userPrompt: String, modelId: String?): Result<SpriteSheetData> = withContext(Dispatchers.IO) {
        val openRouterKey = currentConfig.apiKey.trim()
        val chosenModel = modelId?.ifEmpty { null } 
            ?: currentConfig.model.ifEmpty { "black-forest-labs/flux-1-schnell" }

        val uniformSpritePrompt = """
            2D retro pixel art character sprite sheet, uniform 4x4 grid layout with 16 total frames of equal size and equal spacing.
            Character concept: $userPrompt.
            Row 1: 4-frame walk cycle animation loop facing forward.
            Row 2: 4-frame idle stance animation cycle.
            Row 3: 4-frame weapon attack swing animation cycle.
            Row 4: 4-frame hurt and defeat animation cycle.
            Art style: Crisp 16-bit pixel art, uniform orthographic top-down perspective, high contrast outline, sharp silhouette.
            Solid plain magenta background #FF00FF for chroma key transparency. Exactly 4 rows and 4 columns, perfectly centered within each cell without clipping.
        """.trimIndent()

        // 1. Try OpenRouter image generation if API key is provided
        if (openRouterKey.isNotEmpty()) {
            try {
                var rawImageBytes: ByteArray? = null

                // Attempt A: /images/generations endpoint (standard image models)
                try {
                    val imgReq = OpenAiImageGenerationRequest(
                        prompt = uniformSpritePrompt,
                        model = chosenModel,
                        n = 1,
                        size = "512x512"
                    )
                    val adapter = moshi.adapter(OpenAiImageGenerationRequest::class.java)
                    val respAdapter = moshi.adapter(OpenAiImageGenerationResponse::class.java)
                    val jsonPayload = adapter.toJson(imgReq)
                    val endpoint = "${currentConfig.baseUrl.trimEnd('/')}/images/generations"

                    val httpRequest = Request.Builder()
                        .url(endpoint)
                        .addHeader("Authorization", "Bearer $openRouterKey")
                        .addHeader("HTTP-Referer", "https://ai.studio/build")
                        .addHeader("X-Title", "Igbo ARPG Engine")
                        .addHeader("Content-Type", "application/json")
                        .post(jsonPayload.toRequestBody("application/json".toMediaType()))
                        .build()

                    val httpResponse = okHttpClient.newCall(httpRequest).execute()
                    if (httpResponse.isSuccessful) {
                        val respString = httpResponse.body?.string() ?: ""
                        val parsed = respAdapter.fromJson(respString)
                        val firstItem = parsed?.data?.firstOrNull()
                        if (firstItem?.b64_json != null) {
                            rawImageBytes = android.util.Base64.decode(firstItem.b64_json, android.util.Base64.DEFAULT)
                        } else if (!firstItem?.url.isNullOrEmpty()) {
                            val downloadReq = Request.Builder().url(firstItem!!.url!!).build()
                            val dlResp = okHttpClient.newCall(downloadReq).execute()
                            if (dlResp.isSuccessful) {
                                rawImageBytes = dlResp.body?.bytes()
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Try chat completions route next
                }

                // Attempt B: /chat/completions endpoint (for models returning image markdown or URLs)
                if (rawImageBytes == null) {
                    try {
                        val openAiAdapter = moshi.adapter(OpenAiChatRequest::class.java)
                        val responseAdapter = moshi.adapter(OpenAiChatResponse::class.java)
                        val reqObj = OpenAiChatRequest(
                            model = chosenModel,
                            messages = listOf(
                                OpenAiChatMessage(
                                    role = "system",
                                    content = "You are a specialized 2D game sprite sheet generator. Return an image URL or base64 data for the requested 4x4 sprite sheet."
                                ),
                                OpenAiChatMessage(role = "user", content = uniformSpritePrompt)
                            ),
                            temperature = 0.5
                        )
                        val endpoint = "${currentConfig.baseUrl.trimEnd('/')}/chat/completions"
                        val httpRequest = Request.Builder()
                            .url(endpoint)
                            .addHeader("Authorization", "Bearer $openRouterKey")
                            .addHeader("HTTP-Referer", "https://ai.studio/build")
                            .addHeader("X-Title", "Igbo ARPG Engine")
                            .addHeader("Content-Type", "application/json")
                            .post(openAiAdapter.toJson(reqObj).toRequestBody("application/json".toMediaType()))
                            .build()

                        val httpResponse = okHttpClient.newCall(httpRequest).execute()
                        if (httpResponse.isSuccessful) {
                            val respString = httpResponse.body?.string() ?: ""
                            val parsed = responseAdapter.fromJson(respString)
                            val content = parsed?.choices?.firstOrNull()?.message?.content ?: ""

                            // Check for base64 data URI
                            val base64Match = Regex("data:image/[a-zA-Z]+;base64,([A-Za-z0-9+/=]+)").find(content)
                            if (base64Match != null) {
                                rawImageBytes = android.util.Base64.decode(base64Match.groupValues[1], android.util.Base64.DEFAULT)
                            } else {
                                // Check for image URL
                                val urlMatch = Regex("https://[^\\s)\"]+\\.(?:png|jpg|jpeg|webp)").find(content)
                                    ?: Regex("https://[^\\s)\"]+").find(content)
                                if (urlMatch != null) {
                                    val dlReq = Request.Builder().url(urlMatch.value).build()
                                    val dlResp = okHttpClient.newCall(dlReq).execute()
                                    if (dlResp.isSuccessful) {
                                        rawImageBytes = dlResp.body?.bytes()
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Fall through to procedural engine
                    }
                }

                // If we got raw image bytes from OpenRouter model, process and police it!
                if (rawImageBytes != null && rawImageBytes.isNotEmpty()) {
                    val decodedBitmap = BitmapFactory.decodeByteArray(rawImageBytes, 0, rawImageBytes.size)
                    if (decodedBitmap != null) {
                        // Police sprite: chroma key magenta/corner color to transparent alpha and enforce 4x4
                        val (policedBitmap, policeReport) = SpriteFileTransferManager.policeSpriteBitmap(
                            sourceBitmap = decodedBitmap,
                            targetCols = 4,
                            targetRows = 4,
                            autoChromaKey = true,
                            tolerance = 0.20f
                        )

                        val baos = java.io.ByteArrayOutputStream()
                        policedBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
                        val base64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)

                        val sheet = SpriteSheetData(
                            id = "sprite_${System.currentTimeMillis()}",
                            name = userPrompt.take(24).ifEmpty { "AI Raw Sprite" },
                            description = "Raw OpenRouter Image Model output ($chosenModel): $userPrompt",
                            frameWidth = policeReport.frameWidth,
                            frameHeight = policeReport.frameHeight,
                            columns = 4,
                            rows = 4,
                            isCustomImport = true,
                            rawImageUriOrBase64 = base64,
                            igboThemeRole = "OpenRouter $chosenModel"
                        )
                        return@withContext Result.success(sheet)
                    }
                }
            } catch (e: Exception) {
                // Fall through to procedural fallback
            }
        }

        // 2. Fallback: create high-definition procedural sprite sheet with Igbo styling
        generateSpriteFromPrompt(userPrompt)
    }

    private fun parseAiWeaponResponse(text: String, prompt: String): WeaponItem {
        val lines = text.lines()
        var name = "AI: Ancestral Igbo Relic"
        var baseType = BaseWeaponType.MMA_NKWU
        var rarity = WeaponRarity.LEGENDARY
        var minDmg = 45
        var maxDmg = 75
        var speed = 1.25f
        var mechanic = "Unleashes ancestral sparks on strike."
        var lore = "Cast in the sacred furnace of ancient Igbo metallurgists."

        lines.forEach { line ->
            val upper = line.uppercase()
            when {
                upper.startsWith("NAME:") -> name = line.substringAfter(":").trim()
                upper.startsWith("BASE_TYPE:") -> {
                    val raw = line.substringAfter(":").trim().uppercase()
                    baseType = BaseWeaponType.values().firstOrNull { raw.contains(it.name) } ?: BaseWeaponType.MMA_NKWU
                }
                upper.startsWith("RARITY:") -> {
                    val raw = line.substringAfter(":").trim().uppercase()
                    rarity = WeaponRarity.values().firstOrNull { raw.contains(it.name) } ?: WeaponRarity.LEGENDARY
                }
                upper.startsWith("SPECIAL_MECHANIC:") -> mechanic = line.substringAfter(":").trim()
                upper.startsWith("LORE:") -> lore = line.substringAfter(":").trim()
            }
        }

        return WeaponItem(
            name = name,
            baseType = baseType,
            rarity = rarity,
            itemLevel = 25,
            minDamage = minDmg,
            maxDamage = maxDmg,
            attackSpeed = speed,
            critBonusChance = 0.15f,
            critDamageBonus = 0.6f,
            primaryDamageType = baseType.preferredDamageType,
            lifeLeechPercent = 0.08f,
            affixes = listOf(
                WeaponAffix(
                    name = "AI Inscription",
                    statKey = "Elemental Surge",
                    statBonusValue = 25f,
                    isPercentage = true,
                    loreDescription = "Inspired by user prompt: $prompt"
                )
            ),
            specialMechanic = mechanic,
            nsibidiRune = WeaponStatRandomizer.NSIBIDI_RUNES.random(),
            loreNotes = lore
        )
    }

    private fun parseAiMechanicResponse(text: String, prompt: String): CustomGameMechanic {
        var name = "AI: $prompt".take(28)
        var igboName = "Omenala Ohuru"
        var desc = "When activated, channels ancestral energy to strike all nearby foes."
        var trigger = MechanicTriggerType.ON_HIT
        var proc = 0.35f

        text.lines().forEach { line ->
            val upper = line.uppercase()
            when {
                upper.startsWith("NAME:") -> name = line.substringAfter(":").trim()
                upper.startsWith("IGBO_NAME:") -> igboName = line.substringAfter(":").trim()
                upper.startsWith("DESCRIPTION:") -> desc = line.substringAfter(":").trim()
                upper.startsWith("TRIGGER:") -> {
                    val raw = line.substringAfter(":").trim().uppercase()
                    trigger = MechanicTriggerType.values().firstOrNull { raw.contains(it.name) } ?: MechanicTriggerType.ON_HIT
                }
                upper.startsWith("PROC_CHANCE:") -> {
                    val num = line.substringAfter(":").trim().toFloatOrNull()
                    if (num != null) proc = num.coerceIn(0.05f, 1.0f)
                }
            }
        }

        return CustomGameMechanic(
            id = "ai_mech_${System.currentTimeMillis()}",
            name = name,
            igboName = igboName,
            description = desc,
            isEnabled = true,
            procChance = proc,
            triggerType = trigger
        )
    }

    private fun generateSmartFallbackWeapon(prompt: String): WeaponItem {
        val base = when {
            prompt.contains("staff", true) || prompt.contains("scepter", true) -> BaseWeaponType.OFO_SCEPTER
            prompt.contains("spear", true) || prompt.contains("javelin", true) -> BaseWeaponType.ASA_JAVELIN
            prompt.contains("bow", true) || prompt.contains("arrow", true) -> BaseWeaponType.ULI_SACRED_BOW
            prompt.contains("cleaver", true) || prompt.contains("axe", true) -> BaseWeaponType.IKENGA_CLEAVER
            else -> BaseWeaponType.MMA_NKWU
        }

        return WeaponItem(
            name = "Roped Bronze ${base.title}: $prompt".take(32),
            baseType = base,
            rarity = WeaponRarity.ANCIENT_RELIC,
            itemLevel = 30,
            minDamage = 55,
            maxDamage = 95,
            attackSpeed = 1.35f,
            critBonusChance = 0.18f,
            critDamageBonus = 0.75f,
            primaryDamageType = base.preferredDamageType,
            lifeLeechPercent = 0.12f,
            affixes = listOf(
                WeaponAffix(
                    name = "Igbo-Ukwu Lost-Wax Casting",
                    statKey = "Coil Mastery",
                    statBonusValue = 35f,
                    isPercentage = true,
                    loreDescription = "Intricate concentric wire filigree holds cosmic lightning."
                ),
                WeaponAffix(
                    name = "Nsibidi Inscription of Anyanwu",
                    statKey = "Solar Radiance",
                    statBonusValue = 28f,
                    isPercentage = false,
                    loreDescription = "Heats the bronze to white-hot combustion upon impact."
                )
            ),
            specialMechanic = "Roped Bronze Coils: Striking enemies constricts them with celestial bronze wires for 2 seconds.",
            nsibidiRune = NsibidiRune("☩", "Ọkụ Anyanwu", "Solar Fire", "+28 Fire Damage"),
            loreNotes = "Procedurally crafted from prompt: '$prompt' honoring 9th-century Igbo metallurgical history."
        )
    }

    private fun generateSmartFallbackMechanic(prompt: String): CustomGameMechanic {
        return CustomGameMechanic(
            id = "ai_mech_${System.currentTimeMillis()}",
            name = "Ancestral $prompt".take(26),
            igboName = "Ike Ndi Igbo",
            description = "Custom mechanic generated from '$prompt': On hit, 35% chance to trigger an ancient bronze shockwave dealing 60% bonus damage.",
            procChance = 0.35f,
            triggerType = MechanicTriggerType.ON_HIT,
            numericParameter = 1.5f
        )
    }

    private fun generateSmartFallbackRulebook(prompt: String): PnpRulebook {
        val base = PnpRulebookFactory.createDefaultIgboRulebook()
        return base.copy(
            id = "ai_rulebook_${System.currentTimeMillis()}",
            title = "P&P Module: $prompt".take(36),
            authorNotes = "Generated procedural pen & paper rulebook tailored for: $prompt"
        )
    }

    override suspend fun generateGameThemeFromPrompt(userPrompt: String): Result<GameThemeProfile> = withContext(Dispatchers.IO) {
        val systemPrompt = """
            You are a master game director and worldbuilder for an Action RPG in the style of Diablo and Nintendo Miyamoto design.
            Given a user's theme request (e.g. Cyberpunk, Ancient Egypt, Lovecraftian, Classic Dark Fantasy, Chibi 16-Bit),
            create a cohesive game theme profile with names for classes, skills, weapons, resources, color palette, and a spritesheet prompt.
            Respond strictly in this key-value format:
            THEME_NAME: [Short epic title]
            SUBTITLE: [Evocative 2-4 word tagline]
            LORE: [2 sentence world lore synopsis]
            RECOMMENDED_ERA: [DIABLO_1, DIABLO_2, DIABLO_3, or DIABLO_4]
            HERO_CLASS: [Primary class name, e.g., Street Samurai, Templar, Occultist]
            SKILL_1: [Primary spell/power name]
            SKILL_2: [Secondary ultimate power name]
            HERO_DASH: [Evade/dash name]
            WEAPON_NAME: [Iconic weapon name]
            LIFE_RESOURCE: [Health/Vitality/Sanity/Integrity]
            SPIRIT_RESOURCE: [Mana/Spirit/Energy/RAM]
            BOSS_NAME: [Iconic dungeon boss name]
            FLOOR_HEX: [Hex color, e.g., #1E1E24]
            WALL_HEX: [Hex color, e.g., #0A0A0F]
            PRIMARY_HEX: [Hex color, e.g., #00E5FF]
            ACCENT_HEX: [Hex color, e.g., #FFD700]
            LIFE_HEX: [Hex color, e.g., #E53935]
            MANA_HEX: [Hex color, e.g., #00B0FF]
            SPRITESHEET_PROMPT: [Detailed prompt for a 4x4 pixel art sprite sheet of the hero]
        """.trimIndent()

        val responseText = queryAi(systemPrompt, userPrompt)
        if (responseText.isNotEmpty()) {
            try {
                var themeName = userPrompt.take(28)
                var subtitle = "Custom Universe"
                var lore = "A customized universe created by AI engine, tailored to '$userPrompt'."
                var recommendedEra = DiabloEra.DIABLO_2
                var heroClass = "Hero"
                var skill1 = "Power Strike"
                var skill2 = "Nova Burst"
                var heroDash = "Quick Dash"
                var weapon = "Legendary Blade"
                var lifeRes = "Health"
                var spiritRes = "Mana"
                var boss = "Dungeon Overlord"
                var floorHex = "#1B221E"
                var wallHex = "#111714"
                var primaryHex = "#C6772E"
                var accentHex = "#FFD700"
                var lifeHex = "#E53935"
                var manaHex = "#00B0FF"
                var spritePrompt = "Pixel art sprite sheet 4x4 of $userPrompt, 48x48 frames"

                responseText.lines().forEach { line ->
                    val upper = line.uppercase()
                    when {
                        upper.startsWith("THEME_NAME:") -> themeName = line.substringAfter(":").trim()
                        upper.startsWith("SUBTITLE:") -> subtitle = line.substringAfter(":").trim()
                        upper.startsWith("LORE:") -> lore = line.substringAfter(":").trim()
                        upper.startsWith("RECOMMENDED_ERA:") -> {
                            val eraStr = line.substringAfter(":").trim().uppercase()
                            recommendedEra = DiabloEra.values().firstOrNull { it.name == eraStr } ?: DiabloEra.DIABLO_2
                        }
                        upper.startsWith("HERO_CLASS:") -> heroClass = line.substringAfter(":").trim()
                        upper.startsWith("SKILL_1:") -> skill1 = line.substringAfter(":").trim()
                        upper.startsWith("SKILL_2:") -> skill2 = line.substringAfter(":").trim()
                        upper.startsWith("HERO_DASH:") -> heroDash = line.substringAfter(":").trim()
                        upper.startsWith("WEAPON_NAME:") -> weapon = line.substringAfter(":").trim()
                        upper.startsWith("LIFE_RESOURCE:") -> lifeRes = line.substringAfter(":").trim()
                        upper.startsWith("SPIRIT_RESOURCE:") -> spiritRes = line.substringAfter(":").trim()
                        upper.startsWith("BOSS_NAME:") -> boss = line.substringAfter(":").trim()
                        upper.startsWith("FLOOR_HEX:") -> floorHex = line.substringAfter(":").trim()
                        upper.startsWith("WALL_HEX:") -> wallHex = line.substringAfter(":").trim()
                        upper.startsWith("PRIMARY_HEX:") -> primaryHex = line.substringAfter(":").trim()
                        upper.startsWith("ACCENT_HEX:") -> accentHex = line.substringAfter(":").trim()
                        upper.startsWith("LIFE_HEX:") -> lifeHex = line.substringAfter(":").trim()
                        upper.startsWith("MANA_HEX:") -> manaHex = line.substringAfter(":").trim()
                        upper.startsWith("SPRITESHEET_PROMPT:") -> spritePrompt = line.substringAfter(":").trim()
                    }
                }

                val theme = GameThemeProfile(
                    themeName = themeName,
                    subtitle = subtitle,
                    loreDescription = lore,
                    recommendedEra = recommendedEra,
                    heroClassName = heroClass,
                    heroSkill1Name = skill1,
                    heroSkill2Name = skill2,
                    heroDashName = heroDash,
                    heroWeaponName = weapon,
                    lifeResourceName = lifeRes,
                    spiritResourceName = spiritRes,
                    enemyBossName = boss,
                    floorHex = floorHex,
                    wallHex = wallHex,
                    primaryHex = primaryHex,
                    accentHex = accentHex,
                    lifeColorHex = lifeHex,
                    manaColorHex = manaHex,
                    rawSpritesheetPrompt = spritePrompt,
                    isAiGenerated = true
                )
                return@withContext Result.success(theme)
            } catch (e: Exception) {
                return@withContext Result.success(generateSmartFallbackTheme(userPrompt))
            }
        } else {
            return@withContext Result.success(generateSmartFallbackTheme(userPrompt))
        }
    }

    private fun generateSmartFallbackTheme(prompt: String): GameThemeProfile {
        val lower = prompt.lowercase()
        return when {
            lower.contains("cyber") || lower.contains("neon") || lower.contains("sci-fi") || lower.contains("future") -> {
                GameThemeProfile(
                    themeName = "Cyberpunk: $prompt".take(26),
                    subtitle = "Neon Grid Protocol",
                    loreDescription = "Augmented mercenaries roam the rainy neon towers battling rogue cybernetic syndicates and rogue AI cores.",
                    recommendedEra = DiabloEra.DIABLO_3,
                    heroClassName = "Cyber-Mercenary",
                    heroSkill1Name = "Overcharge EMP",
                    heroSkill2Name = "Plasma Nova",
                    heroDashName = "Nanite Blink",
                    heroWeaponName = "Laser Katana",
                    lifeResourceName = "Armor Integrity",
                    spiritResourceName = "RAM Power",
                    enemyBossName = "Mainframe Golem",
                    floorHex = "#0C1319",
                    wallHex = "#05090D",
                    primaryHex = "#00E5FF",
                    accentHex = "#FF007F",
                    lifeColorHex = "#FF1744",
                    manaColorHex = "#00E5FF",
                    rawSpritesheetPrompt = "16-bit pixel art sprite sheet of a cybernetic mercenary with glowing visor and neon blade, 4x4 grid 48x48",
                    isAiGenerated = true
                )
            }
            lower.contains("horror") || lower.contains("lovecraft") || lower.contains("abyss") || lower.contains("dark") -> {
                GameThemeProfile(
                    themeName = "Abyssal Crypt: $prompt".take(26),
                    subtitle = "Eldritch Sunken Realm",
                    loreDescription = "Occult hunters delve beneath the forsaken tides where forgotten gods stir in eternal nightmare.",
                    recommendedEra = DiabloEra.DIABLO_4,
                    heroClassName = "Occult Slayer",
                    heroSkill1Name = "Void Curse",
                    heroSkill2Name = "Abyssal Rift",
                    heroDashName = "Phase Evade",
                    heroWeaponName = "Obsidian Scythe",
                    lifeResourceName = "Sanity",
                    spiritResourceName = "Eldritch Ichor",
                    enemyBossName = "Leviathan Sovereign",
                    floorHex = "#101818",
                    wallHex = "#070C0C",
                    primaryHex = "#00BFA5",
                    accentHex = "#AA00FF",
                    lifeColorHex = "#C2185B",
                    manaColorHex = "#7C4DFF",
                    rawSpritesheetPrompt = "Pixel art sprite sheet of a gothic occultist with lantern and obsidian blade, 4x4 grid 48x48",
                    isAiGenerated = true
                )
            }
            lower.contains("retro") || lower.contains("pixel") || lower.contains("8-bit") || lower.contains("16-bit") || lower.contains("zelda") -> {
                GameThemeProfile(
                    themeName = "16-Bit Quest: $prompt".take(26),
                    subtitle = "Super Golden Age",
                    loreDescription = "A vibrant fantasy realm filled with secret caves, legendary heart containers, and magical master swords.",
                    recommendedEra = DiabloEra.DIABLO_1,
                    heroClassName = "Hero of Time",
                    heroSkill1Name = "Spin Slash",
                    heroSkill2Name = "Bomb Burst",
                    heroDashName = "Pegasus Dash",
                    heroWeaponName = "Master Blade",
                    lifeResourceName = "Hearts",
                    spiritResourceName = "Magic Meter",
                    enemyBossName = "Ganon Golem",
                    floorHex = "#2E5A1C",
                    wallHex = "#1B3610",
                    primaryHex = "#FFCA28",
                    accentHex = "#29B6F6",
                    lifeColorHex = "#E53935",
                    manaColorHex = "#29B6F6",
                    rawSpritesheetPrompt = "SNES 16-bit pixel art sprite sheet of a green tunic adventurer hero with sword, 4x4 grid 48x48",
                    isAiGenerated = true
                )
            }
            else -> {
                GameThemeProfile(
                    themeName = "Realm of $prompt".take(26),
                    subtitle = "Miyamoto Action RPG",
                    loreDescription = "A procedurally shaped universe generated from '$prompt', balancing legendary weapons and fierce monster encounters.",
                    recommendedEra = DiabloEra.DIABLO_2,
                    heroClassName = "Champion of $prompt".take(20),
                    heroSkill1Name = "Thunder Strike",
                    heroSkill2Name = "Divine Nova",
                    heroDashName = "Heroic Dash",
                    heroWeaponName = "Forged Longsword",
                    lifeResourceName = "Health",
                    spiritResourceName = "Mana",
                    enemyBossName = "Ancient Overlord",
                    floorHex = "#1F2326",
                    wallHex = "#121517",
                    primaryHex = "#FFA000",
                    accentHex = "#00E5FF",
                    lifeColorHex = "#E53935",
                    manaColorHex = "#0288D1",
                    rawSpritesheetPrompt = "Pixel art sprite sheet of a hero suited for $prompt, 4x4 grid, 48x48 pixels per frame",
                    isAiGenerated = true
                )
            }
        }
    }
}

