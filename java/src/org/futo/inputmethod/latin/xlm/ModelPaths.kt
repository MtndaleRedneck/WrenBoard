package org.futo.inputmethod.latin.xlm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.Keep
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.MutableSharedFlow
import org.futo.inputmethod.annotations.ExternallyReferenced
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.utils.JniUtils
import java.io.File
import java.io.FileOutputStream


val BASE_MODEL_RESOURCE = R.raw.ml4_q6_k
val BASE_MODEL_NAME = "ml4_q6_k"
val DEPRECATED_MODEL_NAME = "ml4_1_f16_meta_fixed"

val MODEL_OPTION_KEY = SettingsKey(
    stringSetPreferencesKey("lmModelsByLanguage"),
    setOf("en:$BASE_MODEL_NAME")
)

@Keep
@ExternallyReferenced
data class ModelInfo(
    val name: String,
    val description: String,
    val author: String,
    val license: String,
    val features: List<String>,
    val languages: List<String>,
    val tokenizer_type: String,
    val finetune_count: Int,
    val path: String
) {
    fun toLoader(): ModelInfoLoader {
        return ModelInfoLoader(File(path), name)
    }

    fun isUnsupported(): Boolean {
        return features.isEmpty() || tokenizer_type == "None" || languages.isEmpty()
    }
}

class ModelInfoLoader(
    val path: File,
    val name: String,
) {
    fun loadDetails(): ModelInfo? {
        JniUtils.loadNativeLibrary()
        return loadNative(path.absolutePath)
    }

    external fun loadNative(path: String): ModelInfo?
}

object ModelPaths {
    val modelOptionsUpdated = MutableSharedFlow<Unit>(replay = 0)

    fun exportModel(context: Context, uri: Uri, file: File) {
        context.contentResolver.openOutputStream(uri)!!.use { outputStream ->
            file.inputStream().use { inputStream ->
                var read = 0
                val bytes = ByteArray(1024)
                while (inputStream.read(bytes).also { read = it } != -1) {
                    outputStream.write(bytes, 0, read)
                }
            }
        }
    }


    private val supportedFeatures = setOf(
        "base_v1",
        "inverted_space",
        "xbu_char_autocorrect_v1",
        "lora_finetunable_v1",
        "xc0_swipe_typing_v1",
        "char_embed_mixing_v1",
        "experiment_linear_208_209_210",
    )

    suspend fun signalReloadModels() {
        modelOptionsUpdated.emit(Unit)
    }

    suspend fun updateModelOption(context: Context, key: String, value: File) {
        if(!value.absolutePath.startsWith(context.filesDir.absolutePath)) {
            throw IllegalArgumentException("Model path ${value.absolutePath} does not start with filesDir path ${context.filesDir.absolutePath}")
        }

        val options = context.getSetting(MODEL_OPTION_KEY).filter {
            it.split(":", limit = 2)[0] != key
        }.toMutableSet()

        options.add("$key:${value.nameWithoutExtension}")

        context.setSetting(MODEL_OPTION_KEY, options)

        signalReloadModels()
    }

    suspend fun getModelOptions(context: Context): Map<String, ModelInfoLoader> {
        ensureDefaultModelExists(context)
        val modelDirectory = getModelDirectory(context)
        val options = context.getSetting(MODEL_OPTION_KEY)

        val modelOptionsByLanguage = hashMapOf<String, ModelInfoLoader>()
        options.forEach {
            val splits = it.split(":", limit = 2)
            val language = splits[0]
            var modelName = splits[1]

            if(modelName == DEPRECATED_MODEL_NAME) {
                modelName = BASE_MODEL_NAME
                updateModelOption(context, language, File(modelDirectory, BASE_MODEL_NAME))
            }

            // TODO: This assumes the extension is .gguf
            val modelFile = File(modelDirectory, "$modelName.gguf")
            if(modelFile.exists()) {
                modelOptionsByLanguage[language] = ModelInfoLoader(modelFile, modelName)
            } else {
                Log.e("ModelPaths", "Option for language $language set to $modelName, but could not find ${modelFile.absolutePath}")
            }
        }

        return modelOptionsByLanguage
    }

    fun getModelDirectory(context: Context): File {
        val modelDirectory = File(context.filesDir, "transformer-models")

        if(!modelDirectory.isDirectory){
            modelDirectory.mkdir()
        }

        return modelDirectory
    }

    fun ensureDefaultModelExists(context: Context) {
        val directory = getModelDirectory(context)


        val oldFile = File(directory, "$DEPRECATED_MODEL_NAME.gguf")
        if(oldFile.isFile) oldFile.delete()

        val tgtFile = File(directory, "$BASE_MODEL_NAME.gguf")
        if(!tgtFile.isFile) {
            context.resources.openRawResource(BASE_MODEL_RESOURCE).use { inputStream ->
                FileOutputStream(tgtFile).use { outputStream ->
                    var read = 0
                    val bytes = ByteArray(1024)
                    while (inputStream.read(bytes).also { read = it } != -1) {
                        outputStream.write(bytes, 0, read)
                    }
                }
            }
        }
    }

    fun shouldFileBeIncludedInExport(file: File): Boolean {
        if(file.name == "$BASE_MODEL_NAME.gguf") {
            val loader = ModelInfoLoader(file, file.nameWithoutExtension)
            val info = loader.loadDetails()
            if(info == null) return false
            return info.finetune_count > 0
        } else {
            return true
        }
    }

    fun getModels(context: Context): List<ModelInfoLoader> {
        ensureDefaultModelExists(context)

        return getModelDirectory(context).listFiles()?.map {
            ModelInfoLoader(
                path = it,
                name = it.nameWithoutExtension
            )
        } ?: listOf()
    }
}