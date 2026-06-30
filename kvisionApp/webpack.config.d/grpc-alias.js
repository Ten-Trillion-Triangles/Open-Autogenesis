const grpcPath = require("path");

const grpcDir = grpcPath.resolve(
  __dirname,
  "..",
  "..",
  "..",
  "..",
  "kvisionApp",
  "src",
  "jsMain",
  "resources",
  "grpc"
);

const grpcNodeModules = grpcPath.resolve(__dirname, "..", "..", "node_modules");

config.resolve = config.resolve || {};
config.resolve.alias = {
  ...(config.resolve.alias || {}),
  "@autogenesis/grpc": grpcDir,
};
config.resolve.modules = [
  ...(config.resolve.modules || []),
  grpcNodeModules,
];
