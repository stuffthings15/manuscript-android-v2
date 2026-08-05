package com.echolink.mobile.fixture

import org.junit.Assert.assertEquals
import org.junit.Test

class FixtureSettingsDepartureDiagnosticsTest {
    @Test fun recordsPollQueryConditionAndAssertionTimesWithoutTextContent() {
        val messages = mutableListOf<String>()
        val trace = FixtureSettingsDepartureDiagnostics(
            settingsActivatedAtMs = 1_000L,
            deadlineMs = 1_300L,
            output = messages::add,
        )

        trace.pollStarted(atMs = 1_004L)
        trace.queryCompleted(index = 0, startedAtMs = 1_006L, endedAtMs = 1_017L, absent = false)
        trace.queryCompleted(index = 1, startedAtMs = 1_027L, endedAtMs = 1_035L, absent = true)
        trace.conditionReturned(atMs = 1_036L, result = true)
        trace.assertionStarted(atMs = 1_037L)
        trace.assertionCompleted(atMs = 1_038L)

        assertEquals(emptyList<String>(), messages)
        trace.flush()
        assertEquals(
            listOf(
                "FIXTURE_SETTINGS_DEPARTURE phase=poll_start atMs=1004 afterActivationMs=4 deadlineMs=1300",
                "FIXTURE_SETTINGS_DEPARTURE phase=query index=0 startedAtMs=1006 endedAtMs=1017 " +
                    "startAfterActivationMs=6 endAfterActivationMs=17 durationMs=11 absent=false",
                "FIXTURE_SETTINGS_DEPARTURE phase=query index=1 startedAtMs=1027 endedAtMs=1035 " +
                    "startAfterActivationMs=27 endAfterActivationMs=35 durationMs=8 absent=true",
                "FIXTURE_SETTINGS_DEPARTURE phase=condition_return atMs=1036 afterActivationMs=36 result=true",
                "FIXTURE_SETTINGS_DEPARTURE phase=assertion_start atMs=1037 afterActivationMs=37",
                "FIXTURE_SETTINGS_DEPARTURE phase=assertion_end atMs=1038 afterActivationMs=38",
            ),
            messages,
        )
    }
}
