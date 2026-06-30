// jukebox webpack config
// Webpack automatically copies files from src/jsMain/resources/ to the output directory.
// The Kotlin/JS bundle (jukebox.js) is the entry point.

// Expose the module's @JsExport'd exports as window.JukeboxExports so that
// app.js (or any other browser-side script) can reach the engine members
// (e.g. window.JukeboxExports.jukeboxPlay). JukeboxMain.kt also writes
// window.jukebox = JukeboxAudioEngine as a side effect of module load,
// so app.js uses the simpler window.jukebox.X form.
config.output = config.output || {};
config.output.library = {
  name: "JukeboxExports",
  type: "window"
};

config.module.rules.push({
  test: /\.(mp3|wav|ogg|aac)$/,
  type: 'asset/resource',
  // Emit audio assets at the bundle root, preserving the `audio/...` subpath
  // so the public URL returned by `new URL(path, import.meta.url).href` resolves
  // correctly. For example `./audio/music/ambient_forest.mp3` is emitted to
  // `<bundle>/audio/music/ambient_forest.mp3`.
  generator: {
    filename: '[path][name][ext]'
  }
});
