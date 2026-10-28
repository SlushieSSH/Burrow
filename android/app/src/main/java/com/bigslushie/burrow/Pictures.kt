package com.bigslushie.burrow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.AtomicFile
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.crypto.tink.Aead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException

// Bound to the encrypted pictures on disk: pictures live in this folder, renaming it loses every saved picture.
private const val pictureFolder = "pictures"
// Bound to the encrypted pictures on disk: prefix of each picture's associated data, changing it fails decryption.
private const val pictureAssociatedPrefix = "picture:"
// Bound to the encrypted profile picture on disk: it is the file name and part of the associated data, changing it loses the picture.
const val profilePictureId = "profile"
private const val maxPictureSide = 768
private const val pictureQuality = 88

class PictureStore(private val context: Context, private val aead: () -> Aead) {
    suspend fun save(pictureId: String, source: Uri): Boolean = withContext(Dispatchers.IO) {
        val jpeg = try {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, source)) { decoder, info, _ ->
                val longestSide = maxOf(info.size.width, info.size.height)
                if (longestSide > maxPictureSide) {
                    val scale = maxPictureSide.toFloat() / longestSide
                    decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                }
            }
            ByteArrayOutputStream().also { output -> bitmap.compress(Bitmap.CompressFormat.JPEG, pictureQuality, output) }.toByteArray()
        } catch (e: IOException) {
            return@withContext false
        }
        val file = AtomicFile(pictureFile(pictureId))
        val output = file.startWrite()
        try {
            output.write(aead().encrypt(jpeg, associatedData(pictureId)))
            file.finishWrite(output)
        } catch (e: IOException) {
            file.failWrite(output)
            return@withContext false
        }
        true
    }

    suspend fun load(pictureId: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val file = pictureFile(pictureId)
        if (!file.exists()) return@withContext null
        try {
            val jpeg = aead().decrypt(AtomicFile(file).readFully(), associatedData(pictureId))
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap()
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IOException) {
            null
        }
    }

    suspend fun delete(pictureId: String) {
        withContext(Dispatchers.IO) { AtomicFile(pictureFile(pictureId)).delete() }
    }

    private fun pictureFile(pictureId: String): File = File(File(context.filesDir, pictureFolder).apply { mkdirs() }, "$pictureId.bin")

    private fun associatedData(pictureId: String): ByteArray = (pictureAssociatedPrefix + pictureId).encodeToByteArray()
}
