import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const buildScriptPath = path.join(root, "air3-native-camera-test/build-native-apk.ps1");
const versionLogPath = path.join(root, "docs/air3-v2-version-log.md");
const installVerifyScriptPath = path.join(root, "scripts/install-and-verify-air3-fast.ps1");
const deliveryBuildScriptPath = path.join(root, "scripts/build-air3-delivery-apk.ps1");
const deliveryInstallVerifyScriptPath = path.join(root, "scripts/install-and-verify-air3-delivery.ps1");
const deliveryFinalVerifyScriptPath = path.join(root, "scripts/verify-air3-delivery-final.mjs");
const instantChatBuildScriptPath = path.join(root, "scripts/build-air3-instant-chat-apk.ps1");
const instantChatInstallVerifyScriptPath = path.join(root, "scripts/install-and-verify-air3-instant-chat.ps1");
const dingdangBuildScriptPath = path.join(root, "scripts/build-dingdang-ops-ai-apk.ps1");
const dingdangInstallVerifyScriptPath = path.join(root, "scripts/install-and-verify-dingdang-ops-ai.ps1");
const assistantBuildScriptPath = path.join(root, "scripts/build-dingdang-ai-assistant-apk.ps1");
const assistantInstallVerifyScriptPath = path.join(root, "scripts/install-and-verify-dingdang-ai-assistant.ps1");
const assistantDirectBuildInstallScriptPath = path.join(root, "scripts/build-install-dingdang-ai-assistant-direct-apk.ps1");
const packageJsonPath = path.join(root, "package.json");

const buildScript = fs.readFileSync(buildScriptPath, "utf8");
const versionLog = fs.readFileSync(versionLogPath, "utf8");
const installVerifyScript = fs.readFileSync(installVerifyScriptPath, "utf8");
const deliveryBuildScript = fs.existsSync(deliveryBuildScriptPath) ? fs.readFileSync(deliveryBuildScriptPath, "utf8") : "";
const deliveryInstallVerifyScript = fs.existsSync(deliveryInstallVerifyScriptPath)
  ? fs.readFileSync(deliveryInstallVerifyScriptPath, "utf8")
  : "";
const deliveryFinalVerifyScript = fs.existsSync(deliveryFinalVerifyScriptPath)
  ? fs.readFileSync(deliveryFinalVerifyScriptPath, "utf8")
  : "";
const instantChatBuildScript = fs.existsSync(instantChatBuildScriptPath)
  ? fs.readFileSync(instantChatBuildScriptPath, "utf8")
  : "";
const instantChatInstallVerifyScript = fs.existsSync(instantChatInstallVerifyScriptPath)
  ? fs.readFileSync(instantChatInstallVerifyScriptPath, "utf8")
  : "";
const dingdangBuildScript = fs.existsSync(dingdangBuildScriptPath)
  ? fs.readFileSync(dingdangBuildScriptPath, "utf8")
  : "";
const dingdangInstallVerifyScript = fs.existsSync(dingdangInstallVerifyScriptPath)
  ? fs.readFileSync(dingdangInstallVerifyScriptPath, "utf8")
  : "";
const assistantBuildScript = fs.existsSync(assistantBuildScriptPath)
  ? fs.readFileSync(assistantBuildScriptPath, "utf8")
  : "";
const assistantInstallVerifyScript = fs.existsSync(assistantInstallVerifyScriptPath)
  ? fs.readFileSync(assistantInstallVerifyScriptPath, "utf8")
  : "";
const assistantDirectBuildInstallScript = fs.existsSync(assistantDirectBuildInstallScriptPath)
  ? fs.readFileSync(assistantDirectBuildInstallScriptPath, "utf8")
  : "";
const packageJson = fs.readFileSync(packageJsonPath, "utf8");

function mustInclude(source, marker, message = marker) {
  if (!source.includes(marker)) {
    throw new Error(`native build versioning missing marker: ${message}`);
  }
}

function mustNotInclude(source, marker, message = marker) {
  if (source.includes(marker)) {
    throw new Error(`native build versioning keeps forbidden marker: ${message}`);
  }
}

for (const marker of [
  '$appId = if ($env:AIR3_APK_APP_ID) { $env:AIR3_APK_APP_ID } else { "com.codex.air3nativecamera" }',
  '$appLabel = if ($env:AIR3_APK_APP_LABEL) { $env:AIR3_APK_APP_LABEL } else { "叮当X" }',
  '$outputName = if ($env:AIR3_APK_OUTPUT_NAME) { $env:AIR3_APK_OUTPUT_NAME } else { "Air3NativeCameraTest" }',
  '$manifestForBuild = Join-Path $build "AndroidManifest.xml"',
  '$stringsForBuild = Join-Path $build "res\\values\\strings.xml"',
  'Replace(\'package="com.codex.air3nativecamera"\', "package=`"$appId`"")',
  'Replace(\'android:name=".MainActivity"\', \'android:name="com.codex.air3nativecamera.MainActivity"\')',
  '$generatedDir = Join-Path $generatedSrc "com\\codex\\air3nativecamera"',
  '$classesJar = Join-Path $build "classes.jar"',
  '& $jar --create --file $classesJar -C $classes .',
  '& $d8 --lib $androidJar --output $dex $classesJar',
  'static final boolean FAST_UPLOAD = $fastUpload;',
  'if ($env:AIR3_APK_FAST_UPLOAD -eq "1") {',
  "$versionCode = if ($env:AIR3_APK_VERSION_CODE) { [int]$env:AIR3_APK_VERSION_CODE } else { 215 }",
  '$versionName = if ($env:AIR3_APK_VERSION_NAME) { $env:AIR3_APK_VERSION_NAME } else { "2.0.15" }',
  "$gitOutput = & git -C $repoRoot rev-parse --short HEAD",
  '$localOpsKeyPath = Join-Path $repoRoot "tmp\\ops_glasses_api_key.local"',
  '$localDirectGptKeyPath = Join-Path $repoRoot "tmp\\direct_gpt_api_key.local"',
  '$localDirectAsrKeyPath = Join-Path $repoRoot "tmp\\direct_asr_api_key.local"',
  '$opsKey = $env:OPS_GLASSES_API_KEY.Trim()',
  '(Get-Content -LiteralPath $localOpsKeyPath -Raw).Trim()',
  'if ($env:AIR3_APK_DIRECT_GPT -ne "1" -and $opsKey.Length -eq 0)',
  'throw "OPS_GLASSES_API_KEY missing. Set env:OPS_GLASSES_API_KEY or create tmp/ops_glasses_api_key.local."',
  '$directGptKey = $env:DIRECT_GPT_API_KEY.Trim()',
  'throw "DIRECT_GPT_API_KEY missing. Set env:DIRECT_GPT_API_KEY or create tmp/direct_gpt_api_key.local."',
  '$escapedAppLabel = $appLabel.Replace("\\", "\\\\").Replace(\'"\', \'\\"\')',
  'static final String APP_LABEL = "$escapedAppLabel";',
  'static final boolean DIRECT_GPT_ENABLED = $directGptEnabled;',
  'static final String DINGDANG_BACKEND_BASE_URL = "$escapedDingdangBackendBaseUrl";',
  'static final String DINGDANG_BACKEND_API_KEY = "$escapedDingdangBackendApiKey";',
  'static final String DIRECT_GPT_BASE_URL = "$escapedDirectGptBaseUrl";',
  'static final String DIRECT_GPT_MODEL = "$escapedDirectGptModel";',
  'static final String DIRECT_GPT_API_KEY = "$escapedDirectGptKey";',
  'static final String DIRECT_ASR_ENDPOINT = "$escapedDirectAsrEndpoint";',
  'static final String DIRECT_ASR_API_KEY = "$escapedDirectAsrKey";',
  'Write-Output "OpsKeySource=$opsKeySource"',
  'Write-Output "DirectGptKeySource=$directGptKeySource"',
  "--version-code $versionCode",
  "--version-name $versionName",
  '$outputName-v$versionName-$gitSha.apk',
  "Copy-Item -LiteralPath $signed -Destination $versionedSigned -Force",
  'Write-Output "VersionCode=$versionCode VersionName=$versionName Git=$gitSha"',
]) {
  mustInclude(buildScript, marker);
}

mustNotInclude(buildScript, '--version-code 1', "old versionCode 1 must not remain");
mustNotInclude(buildScript, '--version-name "1.01"', "old versionName 1.01 must not remain");

for (const marker of [
  "v2.0.12-voice-stt-timeout-ux",
  "v2.0.13-voice-diagnostic-hud",
  "v2.0.14-wav-stt-voice",
  "v2.0.15-suspicious-stt-guard",
  "v3.0.0-delivery",
  "`215`",
  "`2.0.15`",
  "`com.codex.air3nativecamera.delivery`",
  "`300`",
  "`3.0.0-delivery`",
  "`叮当保AI`",
  "Air3NativeCameraTest-v<versionName>-<gitSha>.apk",
  "Air3NativeCameraDelivery-v<versionName>-<gitSha>.apk",
  "Air3NativeCameraInstantChat-v<versionName>-<gitSha>.apk",
  "DingdangOpsAi-v<versionName>-<gitSha>.apk",
  "`com.codex.air3nativecamera.dingdangops`",
  "`610`",
  "`6.1.0-asr-final-autostop`",
  "`叮当运维AI`",
  "`com.codex.air3nativecamera.dingdangassistant`",
  "`620`",
  "`6.2.0-assistant-ui-autostop`",
  "`叮当ai助手`",
  "DingdangAiAssistant-v<versionName>-<gitSha>.apk",
  "AIR3_APK_VERSION_CODE",
  "AIR3_APK_VERSION_NAME",
]) {
  mustInclude(versionLog, marker);
}

for (const marker of [
  '"verify:air3-delivery": "node scripts/verify-air3-delivery-final.mjs"',
]) {
  mustInclude(packageJson, marker, `package script must include ${marker}`);
}

for (const marker of [
  'param(',
  '[string]$Serial = "YM00FCF3NW0031"',
  '[string]$Package = "com.codex.air3nativecamera.fast"',
  '[string]$OriginalPackage = "com.codex.air3nativecamera"',
  '[string]$ApkPath = "air3-native-camera-test\\build\\Air3NativeCameraFast.apk"',
  '[int]$ExpectedVersionCode = 222',
  '[string]$ExpectedVersionName = "2.1.6-fast"',
  'adb devices -l',
  'install -r $apk',
  'pm list packages com.codex.air3nativecamera',
  'dumpsys package $Package',
  'versionCode=$ExpectedVersionCode',
  'versionName=$([regex]::Escape($ExpectedVersionName))',
  'resolve-activity --brief $Package',
  'uiautomator dump /dev/tty',
  'screencap -p',
  'last_voice_response.json',
  'last_voice_diagnostics.json',
  'last_ops_response.json',
  '[char]0x62CD',
  '[char]0x8BED',
  '[char]0x4E0A',
]) {
  mustInclude(installVerifyScript, marker, `install-and-verify script must include ${marker}`);
}

for (const marker of [
  '$env:AIR3_APK_APP_ID = "com.codex.air3nativecamera.delivery"',
  '$deliveryLabel = -join @([char]0x53EE, [char]0x5F53, [char]0x4FDD, "AI")',
  '$env:AIR3_APK_APP_LABEL = $deliveryLabel',
  '$env:AIR3_APK_OUTPUT_NAME = "Air3NativeCameraDelivery"',
  '$env:AIR3_APK_VERSION_CODE = "300"',
  '$env:AIR3_APK_VERSION_NAME = "3.0.0-delivery"',
  '$env:AIR3_APK_FAST_UPLOAD = "1"',
  'build-native-apk.ps1',
]) {
  mustInclude(deliveryBuildScript, marker, `delivery build script must include ${marker}`);
}

for (const marker of [
  'param(',
  '[string]$Serial = "YM00FCF3NW0031"',
  '[string]$Package = "com.codex.air3nativecamera.delivery"',
  '[string]$OriginalPackage = "com.codex.air3nativecamera"',
  '[string]$FastPackage = "com.codex.air3nativecamera.fast"',
  '[string]$ApkPath = "air3-native-camera-test\\build\\Air3NativeCameraDelivery.apk"',
  '[int]$ExpectedVersionCode = 300',
  '[string]$ExpectedVersionName = "3.0.0-delivery"',
  '[string]$ExpectedLabel = (-join @([char]0x53EE, [char]0x5F53, [char]0x4FDD, "AI"))',
  'install -r $apk',
  'pm grant $Package android.permission.CAMERA',
  'pm grant $Package android.permission.RECORD_AUDIO',
  'foreach ($expectedPackage in @($OriginalPackage, $FastPackage, $Package))',
  'package:$expectedPackage',
  'Expected package missing after install',
  'versionCode=$ExpectedVersionCode',
  'versionName=$([regex]::Escape($ExpectedVersionName))',
  'resolve-activity --brief $Package',
  'am start --display 0 -n "$Package/$Activity"',
  'uiautomator dump /dev/tty',
  'screencap -p',
  'last_voice_response.json',
  'last_voice_diagnostics.json',
  'last_voice_upload.wav',
  'last_ops_response.json',
]) {
  mustInclude(deliveryInstallVerifyScript, marker, `delivery install-and-verify script must include ${marker}`);
}

for (const marker of [
  'const deliveryPackage = "com.codex.air3nativecamera.delivery"',
  'const deliveryVersionCode = "300"',
  'const deliveryVersionName = "3.0.0-delivery"',
  'const deliveryLabel = "叮当保AI"',
  'npm", ["run", "validate:native-hud"]',
  'npm", ["run", "validate:native-build"]',
  'npm", ["run", "validate:supabase"]',
  'deno", [',
  'aapt2, ["dump", "badging", apkPath]',
  'apksigner, ["verify", "--verbose", apkPath]',
  'scripts/install-and-verify-air3-delivery.ps1',
  'scripts/verify-air3-v2-supabase.mjs',
  'photo.resultType === "network_error"',
  'voice.resultType === "network_error"',
  'photo.resultType !== "instruction"',
  'online voice transcript missing',
]) {
  mustInclude(deliveryFinalVerifyScript, marker, `delivery final verification script must include ${marker}`);
}

for (const marker of [
  '$env:AIR3_APK_APP_ID = "com.codex.air3nativecamera.instantchat"',
  '$instantChatLabel = -join @([char]0x53EE, [char]0x5F53, [char]0x4FDD, "AI-", [char]0x5373, [char]0x65F6, [char]0x5BF9, [char]0x8BDD, [char]0x6D4B, [char]0x8BD5)',
  '$env:AIR3_APK_APP_LABEL = $instantChatLabel',
  '$env:AIR3_APK_OUTPUT_NAME = "Air3NativeCameraInstantChat"',
  '$env:AIR3_APK_VERSION_CODE = "501"',
  '$env:AIR3_APK_VERSION_NAME = "5.0.1-instant-chat"',
  '$env:AIR3_APK_FAST_UPLOAD = "1"',
  'build-native-apk.ps1',
]) {
  mustInclude(instantChatBuildScript, marker, `instant chat build script must include ${marker}`);
}

for (const marker of [
  'param(',
  '[string]$Serial = "YM00FCF3NW0031"',
  '[string]$Package = "com.codex.air3nativecamera.instantchat"',
  '[string]$OriginalPackage = "com.codex.air3nativecamera"',
  '[string]$FastPackage = "com.codex.air3nativecamera.fast"',
  '[string]$DeliveryPackage = "com.codex.air3nativecamera.delivery"',
  '[string]$ApkPath = "air3-native-camera-test\\build\\Air3NativeCameraInstantChat.apk"',
  '[int]$ExpectedVersionCode = 501',
  '[string]$ExpectedVersionName = "5.0.1-instant-chat"',
  '[string]$ExpectedLabel = (-join @([char]0x53EE, [char]0x5F53, [char]0x4FDD, "AI-", [char]0x5373, [char]0x65F6, [char]0x5BF9, [char]0x8BDD, [char]0x6D4B, [char]0x8BD5))',
  'install -r $apk',
  'pm grant $Package android.permission.CAMERA',
  'pm grant $Package android.permission.RECORD_AUDIO',
  'foreach ($expectedPackage in @($OriginalPackage, $FastPackage, $DeliveryPackage, $Package))',
  'versionCode=$ExpectedVersionCode',
  'versionName=$([regex]::Escape($ExpectedVersionName))',
  'resolve-activity --brief $Package',
  'am start --display 0 -n "$Package/$Activity"',
  'uiautomator dump /dev/tty',
  'screencap -p',
  'last_voice_response.json',
  'last_voice_diagnostics.json',
  'last_voice_upload.wav',
  'last_ops_response.json',
]) {
  mustInclude(instantChatInstallVerifyScript, marker, `instant chat install-and-verify script must include ${marker}`);
}

for (const marker of [
  '$env:AIR3_APK_APP_ID = "com.codex.air3nativecamera.dingdangops"',
  '$dingdangLabel = -join @([char]0x53EE, [char]0x5F53, [char]0x8FD0, [char]0x7EF4, "AI")',
  '$env:AIR3_APK_APP_LABEL = $dingdangLabel',
  '$env:AIR3_APK_OUTPUT_NAME = "DingdangOpsAi"',
  '$env:AIR3_APK_VERSION_CODE = "610"',
  '$env:AIR3_APK_VERSION_NAME = "6.1.0-asr-final-autostop"',
  '$env:AIR3_APK_DIRECT_GPT = "0"',
  'build-native-apk.ps1',
]) {
  mustInclude(dingdangBuildScript, marker, `dingdang build script must include ${marker}`);
}

for (const marker of [
  'param(',
  '[string]$Serial = "YM00FCF3NW0031"',
  '[string]$Package = "com.codex.air3nativecamera.dingdangops"',
  '[string]$ApkPath = "air3-native-camera-test\\build\\DingdangOpsAi.apk"',
  '[int]$ExpectedVersionCode = 610',
  '[string]$ExpectedVersionName = "6.1.0-asr-final-autostop"',
  '[string]$ExpectedLabel = (-join @([char]0x53EE, [char]0x5F53, [char]0x8FD0, [char]0x7EF4, "AI"))',
  'install -r $apk',
  'pm grant $Package android.permission.CAMERA',
  'pm grant $Package android.permission.RECORD_AUDIO',
  'Expected package missing after install: $Package',
  'versionCode=$ExpectedVersionCode',
  'versionName=$([regex]::Escape($ExpectedVersionName))',
  'resolve-activity --brief $Package',
  'am start --display 0 -n "$Package/$Activity"',
  'uiautomator dump /dev/tty',
  'screencap -p',
  '$photoMarker = -join @([char]0x70B9, [char]0x6211, [char]0x62CD, [char]0x7167)',
  '$voiceMarker = -join @([char]0x70B9, [char]0x6211, [char]0x8BF4, [char]0x8BDD)',
  '$speakMarker = -join @([char]0x70B9, [char]0x6211, [char]0x8BF4, [char]0x8BDD)',
  'foreach ($marker in @($ExpectedLabel, $photoMarker, $voiceMarker, $speakMarker))',
]) {
  mustInclude(dingdangInstallVerifyScript, marker, `dingdang install-and-verify script must include ${marker}`);
}

for (const marker of [
  '$env:AIR3_APK_APP_ID = "com.codex.air3nativecamera.dingdangassistant"',
  '$assistantLabel = -join @([char]0x53EE, [char]0x5F53, "ai", [char]0x52A9, [char]0x624B)',
  '$env:AIR3_APK_APP_LABEL = $assistantLabel',
  '$env:AIR3_APK_OUTPUT_NAME = "DingdangAiAssistant"',
  '$env:AIR3_APK_VERSION_CODE = "620"',
  '$env:AIR3_APK_VERSION_NAME = "6.2.0-assistant-ui-autostop"',
  '$env:AIR3_APK_DIRECT_GPT = "0"',
  'build-native-apk.ps1',
]) {
  mustInclude(assistantBuildScript, marker, `assistant build script must include ${marker}`);
}

for (const marker of [
  'param(',
  '[string]$Serial = "YM00FCF3NW0031"',
  '[string]$Package = "com.codex.air3nativecamera.dingdangassistant"',
  '[string]$OldPackage = "com.codex.air3nativecamera.dingdangops"',
  '[string]$ApkPath = "air3-native-camera-test\\build\\DingdangAiAssistant.apk"',
  '[int]$ExpectedVersionCode = 620',
  '[string]$ExpectedVersionName = "6.2.0-assistant-ui-autostop"',
  '[string]$ExpectedLabel = (-join @([char]0x53EE, [char]0x5F53, "ai", [char]0x52A9, [char]0x624B))',
  'install -r $apk',
  'pm grant $Package android.permission.CAMERA',
  'pm grant $Package android.permission.RECORD_AUDIO',
  'foreach ($requiredPackage in @($OldPackage, $Package))',
  'Expected package missing after assistant install',
  'versionCode=$ExpectedVersionCode',
  'versionName=$([regex]::Escape($ExpectedVersionName))',
  'resolve-activity --brief $Package',
  'am start --display 0 -n "$Package/$Activity"',
  'uiautomator dump /dev/tty',
  'screencap -p',
  '$photoMarker = -join @([char]0x70B9, [char]0x6211, [char]0x62CD, [char]0x7167)',
  '$voiceMarker = -join @([char]0x70B9, [char]0x6211, [char]0x8BF4, [char]0x8BDD)',
]) {
  mustInclude(assistantInstallVerifyScript, marker, `assistant install-and-verify script must include ${marker}`);
}

for (const marker of [
  'scripts\\build-dingdang-ai-assistant-apk.ps1',
  'scripts\\install-and-verify-dingdang-ai-assistant.ps1',
  'DIRECT_GPT_API_KEY',
  'DIRECT_ASR_API_KEY',
  'DIRECT_ASR_ENDPOINT',
  'Key values are loaded but will not be printed',
]) {
  mustInclude(assistantDirectBuildInstallScript, marker, `assistant direct build/install script must include ${marker}`);
}

console.log("Native build versioning validation passed.");
