package org.noteflow.app.offline

import android.content.Context
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** User-supplied Vosk model; app never fetches or uploads model files automatically. */
object ModelInstaller {
    private const val LIMIT_BYTES = 1_500_000_000L
    fun installed(context: Context): Boolean = File(context.filesDir, "speech-model/am/final.mdl").isFile
    fun path(context: Context): File = File(context.filesDir, "speech-model")

    /** Checks supplied archive SHA-256 and blocks zip slip, decompression bombs and symlinks. */
    fun install(context: Context, source: InputStream, expectedSha256: String): File {
        require(Regex("^[a-fA-F0-9]{64}$").matches(expectedSha256)) { "Enter the expected 64-digit SHA-256 from the model publisher" }
        val staging=File(context.cacheDir,"speech-install-${System.currentTimeMillis()}").apply { mkdirs() }
        val archive=File(staging,"model.zip")
        try {
            val digest=MessageDigest.getInstance("SHA-256")
            source.use { input -> archive.outputStream().use { output ->
                val buffer=ByteArray(65536); var size=0L
                while(true) { val count=input.read(buffer); if(count<0)break
                    size += count; require(size <= LIMIT_BYTES) { "Model archive too large" }
                    digest.update(buffer,0,count);output.write(buffer,0,count)
                }
            } }
            val actual=digest.digest().joinToString("") { "%02x".format(it) }
            require(actual.equals(expectedSha256,true)) { "Model checksum mismatch; model not installed" }
            val temp=File(staging,"unpacked").apply{mkdirs()}
            val root=temp.canonicalFile
            var total=0L;var entries=0
            ZipInputStream(archive.inputStream()).use { zip ->
                while (true) {
                    val entry=zip.nextEntry ?: break
                    entries++;require(entries < 40000) { "Too many model files" }
                    val name=entry.name.replace('\\','/')
                    require(!name.startsWith('/') && !name.contains("../") && !name.startsWith("../")) { "Unsafe model path" }
                    val target=File(temp,name).canonicalFile
                    require(target.path.startsWith(root.path+File.separator)) { "Unsafe archive path" }
                    if(entry.isDirectory)target.mkdirs() else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out ->
                            val chunk=ByteArray(65536)
                            while (true) {
                                val read=zip.read(chunk);if(read<0)break
                                total += read;require(total <= LIMIT_BYTES*2) { "Unpacked model too large" }
                                out.write(chunk,0,read)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            // Accept either a root model or a single folder containing the model.
            val modelRoot= if(File(temp,"am/final.mdl").isFile) temp else
                temp.listFiles()?.firstOrNull { File(it,"am/final.mdl").isFile } ?: error("Not a Vosk model: am/final.mdl missing")
            val destination=path(context)
            val swap=File(context.filesDir,"speech-model-new")
            if(swap.exists())swap.deleteRecursively()
            require(modelRoot.copyRecursively(swap,overwrite=true)) { "Model installation failed" }
            // Never discard a working model until new data is fully copied.
            if(destination.exists())destination.deleteRecursively()
            check(swap.renameTo(destination)) { "Could not activate model" }
            return destination
        } finally { staging.deleteRecursively() }
    }
}
