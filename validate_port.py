from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parent


def main() -> int:
    source_root = ROOT / "app/src/main"
    sources = "\n".join(path.read_text(encoding="utf-8") for path in source_root.rglob("*.kt"))
    accessibility_source = (
        source_root / "java/com/echolink/mobile/ManuscriptAccessibilityService.kt"
    ).read_text(encoding="utf-8")
    manifest = (source_root / "AndroidManifest.xml").read_text(encoding="utf-8")
    strings = (source_root / "res/values/strings.xml").read_text(encoding="utf-8")
    ids = (source_root / "res/values/ids.xml").read_text(encoding="utf-8")
    accessibility_config = (
        source_root / "res/xml/accessibility_service_config.xml"
    ).read_text(encoding="utf-8")
    app_gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")

    for marker in (
        "Manuscript",
        "Forge Perfect Writing",
        "Replace Text",
        "Dismiss",
    ):
        assert marker in sources or marker in strings, f"Missing Manuscript marker: {marker}"

    for marker in (
        "WritingTone.entries",
        "WritingReviewSession.create",
        "WritingAssistUiPolicy.shouldShowIcon",
        "Intent.ACTION_PROCESS_TEXT",
        "AndroidLocalPersonalToolsStore",
        "ManuscriptAccessibilityService",
    ):
        assert marker in sources, f"Missing Android behavior: {marker}"

    for resource_id in (
        "writing_assist_icon",
        "writing_suggestion_card",
        "writing_counter",
        "writing_close_action",
        "writing_suggestion",
        "writing_tone",
        "writing_regenerate_action",
        "writing_replace_action",
        "writing_dismiss_action",
    ):
        assert f'name="{resource_id}"' in ids, f"Missing stable UI ID: {resource_id}"

    assert 'name="writing_original"' not in ids, "External review must not repeat the source text"
    assert "R.string.original_label" not in accessibility_source
    assert "R.id.writing_original" not in accessibility_source
    for marker in (
        "WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY",
        "KeyboardPanelGeometry.calculate",
        "navigationBottomInsetPx",
        "R.string.suggestion_label",
        "R.id.writing_regenerate_action",
        "AccessibilityNodeInfo.ACTION_SET_TEXT",
        "closePanel(restoreKeyboard = true)",
        "AccessibilityNodeInfo.ACTION_FOCUS",
        "AccessibilityNodeInfo.ACTION_CLICK",
    ):
        assert marker in accessibility_source, f"Missing across-app behavior: {marker}"

    assert "android.intent.action.PROCESS_TEXT" in manifest
    assert 'android:mimeType="text/plain"' in manifest
    assert 'android:allowBackup="false"' in manifest
    assert 'android:usesCleartextTraffic="false"' in manifest
    assert 'applicationId "com.remote.Manuscript.v2"' in app_gradle
    assert 'android:name=".ManuscriptAccessibilityService"' in manifest
    assert 'android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"' in manifest
    assert "android.accessibilityservice.AccessibilityService" in manifest
    assert 'android:resource="@xml/accessibility_service_config"' in manifest
    assert 'android:canRetrieveWindowContent="true"' in accessibility_config
    for forbidden in (
        "android.permission.INTERNET",
        "android.permission.RECORD_AUDIO",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.BIND_INPUT_METHOD",
        "SpeechRecognizer",
        "InputMethodService",
        "ClipboardManager",
        "ClipData",
    ):
        assert forbidden not in manifest + sources, f"Forbidden Android capability: {forbidden}"

    assert "Creator Studios" not in manifest + strings + sources
    print(
        "Manuscript validation passed: local writing, explicit accessibility helper, "
        "keyboard-area review, hidden source text, guarded replacement, and system keyboard return."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
