package ca.saboor.discs.screen

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import su.plo.slib.api.logging.McLoggerFactory

object FFmpegResolver {

    private val logger = org.slf4j.LoggerFactory.getLogger("FFmpegResolver")

    @Volatile
    private var cachedResolvedPath: String? = null

    val isWindows: Boolean = System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")
    private val binaryName: String = if (isWindows) "ffmpeg.exe" else "ffmpeg"

    /**
     * Resolves an executable path for FFmpeg.
     * Searches in:
     * 1. Explicitly configured path in discs.toml
     * 2. Local server/plugin directory: plugins/vc-addon-discs/bin/ffmpeg, plugins/vc-addon-discs/ffmpeg, ./ffmpeg
     * 3. System PATH
     * 4. If autoInstall is true, attempts to download and unpack a portable static FFmpeg binary.
     */
    suspend fun resolve(
        dataFolder: File,
        configuredPath: String = "ffmpeg",
        autoInstall: Boolean = true
    ): String? {
        cachedResolvedPath?.let { cached ->
            if (testExecutable(cached)) return cached
            cachedResolvedPath = null
        }

        return withContext(Dispatchers.IO) {
            // 1. Check configured path if custom
            if (configuredPath != "ffmpeg") {
                val candidates = listOf(
                    File(configuredPath),
                    File(dataFolder, configuredPath)
                )
                for (file in candidates) {
                    if (file.exists() && file.isFile) {
                        trySetExecutable(file)
                        if (testExecutable(file.absolutePath)) {
                            logger.info("Found custom FFmpeg at ${file.absolutePath}")
                            cachedResolvedPath = file.absolutePath
                            return@withContext file.absolutePath
                        }
                    }
                }
            }

            // 2. Check local plugin and server directory candidates
            val localCandidates = listOf(
                File(dataFolder, "bin/$binaryName"),
                File(dataFolder, "bin/ffmpeg"),
                File(dataFolder, "bin/ffmpeg.exe"),
                File(dataFolder, binaryName),
                File(dataFolder, "ffmpeg"),
                File(dataFolder, "ffmpeg.exe"),
                File(binaryName),
                File("ffmpeg"),
                File("ffmpeg.exe")
            ).distinct()

            for (file in localCandidates) {
                if (file.exists() && file.isFile) {
                    trySetExecutable(file)
                    if (testExecutable(file.absolutePath)) {
                        logger.info("Found portable FFmpeg at ${file.absolutePath}")
                        cachedResolvedPath = file.absolutePath
                        return@withContext file.absolutePath
                    } else {
                        logger.warn("Found candidate FFmpeg binary at '${file.absolutePath}', but execution test failed! (canExecute=${file.canExecute()}, length=${file.length()}). Check container permissions or binary architecture.")
                    }
                }
            }

            // 3. Check system PATH
            if (testExecutable("ffmpeg")) {
                logger.info("Using system FFmpeg from PATH")
                cachedResolvedPath = "ffmpeg"
                return@withContext "ffmpeg"
            }

            // 4. If auto-install is enabled, download static portable binary
            if (autoInstall) {
                val installed = installStaticFFmpeg(dataFolder)
                if (installed != null) {
                    cachedResolvedPath = installed
                    return@withContext installed
                }
            }

            null
        }
    }

    fun clearCache() {
        cachedResolvedPath = null
    }

    fun testExecutable(path: String): Boolean {
        return try {
            val file = File(path)
            if (file.exists() && file.isFile) {
                trySetExecutable(file)
            }
            val process = ProcessBuilder(path, "-version")
                .redirectErrorStream(true)
                .start()
            val ok = process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0
            process.destroyForcibly()
            ok
        } catch (e: Throwable) {
            logger.debug("testExecutable failed for '$path': ${e.message}")
            false
        }
    }

    fun trySetExecutable(file: File) {
        try {
            if (!file.canExecute()) {
                file.setExecutable(true, false)
            }
            if (!file.canRead()) {
                file.setReadable(true, false)
            }
        } catch (_: Throwable) {}
    }

    /**
     * Downloads and installs a platform-specific static portable FFmpeg binary into plugins/vc-addon-discs/bin/.
     */
    suspend fun installStaticFFmpeg(dataFolder: File, onProgress: ((String) -> Unit)? = null): String? {
        return withContext(Dispatchers.IO) {
            val downloadUrl = getDownloadUrlForCurrentPlatform()
            if (downloadUrl == null) {
                val os = System.getProperty("os.name")
                val arch = System.getProperty("os.arch")
                logger.warn("Automatic FFmpeg download is not supported for platform: $os ($arch). Please upload a static ffmpeg binary to 'plugins/vc-addon-discs/bin/ffmpeg'.")
                onProgress?.invoke("Automatic FFmpeg download not supported for $os ($arch).")
                return@withContext null
            }

            val binDir = File(dataFolder, "bin")
            binDir.mkdirs()
            val targetFile = File(binDir, binaryName)
            val tempZip = File(binDir, "ffmpeg-download.tmp")

            try {
                logger.info("Downloading static portable FFmpeg from $downloadUrl ...")
                onProgress?.invoke("Downloading portable FFmpeg...")

                val conn = URI(downloadUrl).toURL().openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (vc-addon-discs)")
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.instanceFollowRedirects = true

                // Follow redirects manually if needed (GitHub releases redirect to AWS S3)
                var currentConn = conn
                var status = currentConn.responseCode
                var redirects = 0
                while ((status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_MOVED_PERM || status == 307 || status == 308) && redirects < 5) {
                    val location = currentConn.getHeaderField("Location") ?: break
                    currentConn.disconnect()
                    currentConn = URI(location).toURL().openConnection() as HttpURLConnection
                    currentConn.setRequestProperty("User-Agent", "Mozilla/5.0 (vc-addon-discs)")
                    currentConn.connectTimeout = 15000
                    currentConn.readTimeout = 60000
                    status = currentConn.responseCode
                    redirects++
                }

                if (status != HttpURLConnection.HTTP_OK) {
                    throw IOException("HTTP download failed with status $status")
                }

                currentConn.inputStream.use { input ->
                    tempZip.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                logger.info("Unpacking FFmpeg binary to ${targetFile.absolutePath} ...")
                onProgress?.invoke("Extracting FFmpeg binary...")

                ZipFile(tempZip).use { zip ->
                    val entry = zip.entries().asSequence().firstOrNull {
                        val name = it.name.lowercase(Locale.ROOT)
                        name == "ffmpeg" || name == "ffmpeg.exe" || name.endsWith("/ffmpeg") || name.endsWith("/ffmpeg.exe")
                    } ?: throw IllegalStateException("ffmpeg binary not found inside downloaded archive")

                    zip.getInputStream(entry).use { entryInput ->
                        targetFile.outputStream().use { targetOutput ->
                            entryInput.copyTo(targetOutput)
                        }
                    }
                }

                tempZip.delete()
                trySetExecutable(targetFile)

                if (testExecutable(targetFile.absolutePath)) {
                    logger.info("Static portable FFmpeg installed successfully at ${targetFile.absolutePath}")
                    onProgress?.invoke("Static portable FFmpeg installed and verified successfully!")
                    cachedResolvedPath = targetFile.absolutePath
                    return@withContext targetFile.absolutePath
                } else {
                    logger.warn("FFmpeg downloaded to ${targetFile.absolutePath} but failed execution test (container may enforce noexec).")
                    onProgress?.invoke("FFmpeg downloaded but failed execution test (container may enforce noexec).")
                    return@withContext null
                }
            } catch (e: Exception) {
                tempZip.delete()
                logger.error("Failed to automatically download and unpack FFmpeg: ${e.message}", e)
                onProgress?.invoke("Failed to install FFmpeg: ${e.message}")
                return@withContext null
            }
        }
    }

    private fun getDownloadUrlForCurrentPlatform(): String? {
        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        val arch = System.getProperty("os.arch").lowercase(Locale.ROOT)

        val isArm = arch.contains("aarch64") || arch.contains("arm64")
        val isX64 = arch.contains("amd64") || arch.contains("x86_64")

        return when {
            os.contains("linux") && isX64 ->
                "https://github.com/ffbinaries/ffbinaries-prebuilt/releases/download/v6.1/ffmpeg-6.1-linux-64.zip"
            os.contains("linux") && isArm ->
                "https://github.com/ffbinaries/ffbinaries-prebuilt/releases/download/v6.1/ffmpeg-6.1-linux-arm-64.zip"
            os.contains("win") && isX64 ->
                "https://github.com/ffbinaries/ffbinaries-prebuilt/releases/download/v6.1/ffmpeg-6.1-win-64.zip"
            (os.contains("mac") || os.contains("darwin")) && isX64 ->
                "https://github.com/ffbinaries/ffbinaries-prebuilt/releases/download/v6.1/ffmpeg-6.1-macos-64.zip"
            else -> null
        }
    }
}

