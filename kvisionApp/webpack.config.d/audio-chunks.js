/**
 * Webpack configuration for lazy-loading audio files.
 * Audio files in resources/audio/ are bundled as separate chunks
 * and loaded on demand via dynamic import().
 *
 * Safari supports AAC via .m4a (MP4 container with AAC codec) and .aac raw container.
 * Firefox supports Opus via .ogg (Ogg container) and .opus.
 * Chrome supports both. We bundle all formats so [AudioResourceLoader] can try
 * multiple paths at runtime and use whichever the browser can decode.
 */
config.module.rules.push({
  test: /\.(ogg|mp3|wav|aac|m4a|opus)$/,
  type: "asset/resource",
  generator: {
    filename: "audio/[name][ext]"
  }
});

config.optimization = config.optimization || {};
config.optimization.splitChunks = config.optimization.splitChunks || {};
config.optimization.splitChunks.cacheGroups = config.optimization.splitChunks.cacheGroups || {};
config.optimization.splitChunks.cacheGroups.audio = {
  name: "audio",
  type: "asset/resource",
  chunks: "all",
  enforce: true
};
