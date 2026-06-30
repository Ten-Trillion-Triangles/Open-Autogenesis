package org.ttt.autogenesis.mapeditor

import globals.KEnv
import io.kvision.Application
import io.kvision.BootstrapCssModule
import io.kvision.BootstrapModule
import io.kvision.CoreModule
import io.kvision.html.Button
import io.kvision.html.H1
import io.kvision.html.Input
import io.kvision.html.InputType
import io.kvision.html.button
import io.kvision.html.input
import io.kvision.panel.Root
import io.kvision.panel.root
import io.kvision.panel.simplePanel
import io.kvision.startApplication
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLInputElement
import org.w3c.files.FileReader
import ui.EditorPanel
import ui.MapCanvas

class MapEditorApp : Application() {

    private val coroutineScope = MainScope()

    override fun start(state: Map<String, Any>) {
        lateinit var hiddenFileInput: Input
        KEnv.setRoot(root("kvapp") {
            KEnv.setBackgroundImage("img/AutogenesisTitle.png")
            val editorPanel = EditorPanel()
            add(editorPanel)
            KEnv.saveWidget(editorPanel)
        })
    }
}

fun main() {
    startApplication(
        ::MapEditorApp,
        js("module.hot"),
        BootstrapModule,
        CoreModule,
        BootstrapCssModule
    )
}
