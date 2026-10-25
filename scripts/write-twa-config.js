#!/usr/bin/env node
// 由环境变量渲染 twa/twa.config.json，供 GitHub Actions 使用。
const fs = require("fs");
const path = require("path");

const config = {
  host: process.env.TWA_HOST || "octop.example.com",
  startUrl: "/",
  applicationId: process.env.TWA_APP_ID || "com.example.octop",
  appName: process.env.TWA_APP_NAME || "Octop",
  launcherName: process.env.TWA_APP_NAME || "Octop",
  versionCode: Number(process.env.TWA_VERSION_CODE || 1),
  versionName: process.env.TWA_VERSION_NAME || "1.0.0",
  themeColor: "#ffffff",
  backgroundColor: "#0f1117",
  iconUrl: `https://${process.env.TWA_HOST}/pwa-512.png`,
  maskableIconUrl: "",
  minSdkVersion: 26,
  orientation: "default",
  enableNotifications: false,
};

const target = path.join(__dirname, "..", "twa", "twa.config.json");
fs.writeFileSync(target, JSON.stringify(config, null, 2) + "\n");
console.log(`wrote ${target}`);
