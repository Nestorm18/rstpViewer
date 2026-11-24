plugins {
    id("com.android.application")
}

android {
    namespace = "com.rtspviewer.panel"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rtspviewer.panel"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.5"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Firmado con la clave debug para poder instalarlo directamente
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

/**
 * La página del panel es la misma que la del panel de Windows: se saca de pc/panel_central.py
 * (la cadena PAGE) al compilar, con unos pocos cambios para la tablet. Así solo hay una copia.
 * Si un cambio ya no encaja con la página, la compilación falla para que se revise.
 */
abstract class PanelPageTask : DefaultTask() {
    @get:InputFile
    abstract val source: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val py = source.get().asFile.readText(Charsets.UTF_8)
        val marker = "PAGE = r\"\"\""
        val start = py.indexOf(marker)
        require(start >= 0) { "No se encuentra PAGE en panel_central.py" }
        val end = py.indexOf("\"\"\"", start + marker.length)
        require(end > start) { "PAGE sin cerrar en panel_central.py" }
        var page = py.substring(start + marker.length, end)

        val changes = listOf(
            // En la tablet no hay PowerPoint para convertir presentaciones.
            "accept=\".jpg,.jpeg,.png,.webp,.mp4,.mkv,.webm,.pptx,.ppt,.ppsx,.pps\"" to
                "accept=\".jpg,.jpeg,.png,.webp,.mp4,.mkv,.webm\"",
            Regex("""<p class="hint">Imagen, vídeo o PowerPoint\..*?</p>""", RegexOption.DOT_MATCHES_ALL) to
                "<p class=\"hint\">Imagen (JPG, PNG, WEBP) o vídeo (MP4, MKV, WEBM). Los PowerPoint solo se pueden " +
                "convertir desde el panel central del PC.<input type=\"number\" id=\"secs\" hidden></p>",
            "¿Sigue abierto el programa en el PC?" to "Cierra la app Panel Central y vuelve a abrirla.",
            "<title>RTSP Viewer · Panel central</title>" to "<title>Panel central</title>",
        )
        for ((from, to) in changes) {
            page = when (from) {
                is Regex -> {
                    require(from.containsMatchIn(page)) { "La página del panel ha cambiado: no se encuentra $from" }
                    from.replace(page, Regex.escapeReplacement(to))
                }
                else -> {
                    val s = from as String
                    require(page.contains(s)) { "La página del panel ha cambiado: no se encuentra «$s»" }
                    page.replace(s, to)
                }
            }
        }
        val out = outputDir.get().asFile
        out.mkdirs()
        out.resolve("panel.html").writeText(page, Charsets.UTF_8)
    }
}

val panelPage = tasks.register<PanelPageTask>("panelPage") {
    source.set(rootProject.layout.projectDirectory.file("pc/panel_central.py"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(panelPage, PanelPageTask::outputDir)
    }
}
