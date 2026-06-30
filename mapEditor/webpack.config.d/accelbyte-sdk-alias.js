const path = require("path");

const accelbyteBase = path.resolve(
  __dirname,
  "..",
  "..",
  "..",
  "..",
  "accelbyte",
  "accelbyte-typescript-sdk",
  "packages"
);

const accelbytePackages = [
  "sdk-iam",
  "sdk-session",
  "sdk-cloudsave",
  "sdk-platform",
  "sdk-chat",
  "sdk-social",
  "sdk-groups",
  "sdk-inventory",
  "sdk-legal",
  "sdk-lobby",
  "sdk-matchmaking",
  "sdk-gametelemetry",
  "sdk-differ",
  "sdk-buildinfo",
  "sdk-basic",
  "sdk-leaderboard",
  "sdk-achievement",
  "sdk-qosmanager",
  "sdk-event",
  "sdk-seasonpass",
  "sdk-reporting",
  "sdk-ugc",
  "sdk-audit",
  "sdk-gdpr",
  "sdk-csm",
  "sdk-config",
  "sdk-dsmcontroller",
  "sdk-ams",
  "sdk-ehs",
  "sdk-challenge",
  "sdk-login-queue"
];

const alias = accelbytePackages.reduce((acc, pkg) => {
  const mainPath =
    pkg === "sdk"
      ? path.resolve(accelbyteBase, pkg, "dist", "es", "browser", "index.browser.js")
      : path.resolve(accelbyteBase, pkg, "dist", "index.js");
  acc[`@accelbyte/${pkg}`] = mainPath;
  acc[`@accelbyte/${pkg}/dist/index.js`] = mainPath;
  return acc;
}, {});

config.resolve = config.resolve || {};
config.resolve.alias = {
  ...(config.resolve.alias || {}),
  ...alias
};
