package com.echolink.mobile.fixture

class FixtureSettingsDepartureDiagnostics(
    private val settingsActivatedAtMs: Long,
    private val deadlineMs: Long,
    private val output: (String) -> Unit,
) {
    private val pending = mutableListOf<String>()

    fun pollStarted(atMs: Long) {
        pending +=
            "FIXTURE_SETTINGS_DEPARTURE phase=poll_start atMs=$atMs " +
                "afterActivationMs=${atMs - settingsActivatedAtMs} deadlineMs=$deadlineMs"
    }

    fun queryCompleted(index: Int, startedAtMs: Long, endedAtMs: Long, absent: Boolean) {
        pending +=
            "FIXTURE_SETTINGS_DEPARTURE phase=query index=$index " +
                "startedAtMs=$startedAtMs endedAtMs=$endedAtMs " +
                "startAfterActivationMs=${startedAtMs - settingsActivatedAtMs} " +
                "endAfterActivationMs=${endedAtMs - settingsActivatedAtMs} " +
                "durationMs=${endedAtMs - startedAtMs} absent=$absent"
    }

    fun conditionReturned(atMs: Long, result: Boolean) {
        pending +=
            "FIXTURE_SETTINGS_DEPARTURE phase=condition_return atMs=$atMs " +
                "afterActivationMs=${atMs - settingsActivatedAtMs} result=$result"
    }

    fun assertionStarted(atMs: Long) {
        pending +=
            "FIXTURE_SETTINGS_DEPARTURE phase=assertion_start atMs=$atMs " +
                "afterActivationMs=${atMs - settingsActivatedAtMs}"
    }

    fun assertionCompleted(atMs: Long) {
        pending +=
            "FIXTURE_SETTINGS_DEPARTURE phase=assertion_end atMs=$atMs " +
                "afterActivationMs=${atMs - settingsActivatedAtMs}"
    }

    fun flush() {
        pending.forEach(output)
        pending.clear()
    }
}
