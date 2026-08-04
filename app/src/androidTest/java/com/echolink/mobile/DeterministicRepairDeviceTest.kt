package com.echolink.mobile

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Model-free check of the repair the pipeline falls back on. It runs in
 * seconds and answers the only question that matters when a corrected
 * paragraph still contains misspellings: does the deterministic layer fix
 * them at all?
 */
@RunWith(AndroidJUnit4::class)
class DeterministicRepairDeviceTest {
    @Test
    fun repairsPlainMisspellings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val router = LocalCorrectionRouter(context)
        val samples = listOf(
            "Some peeple will disaagree befor hearing the resons.",
            "the animal can belong in a hoem.",
            "a dog got habits, neesd,, and a personallity.",
            "only in a diffrent looing package..",
            "A Parrot Make a Colorful Pett in Cairns",
            "Turtles is a patient pet in Valletta",
            "The analysis is complete and the news is good.",
            "Ducks is funny pets in Dalat",
            "In DLAT, Vietnam, THIS can be true and the USA agrees.",
            "land beyond the city, city, and why it nees to fit,,",
            "Wen somebody says a certain animal cant be a pet in Leipzig.",
            "they usually is speaking and I is tired but she is fine.",
            "renting one room. Room, and why this esay works.",
            "a pett IN PUEBLA and an esay about it",
            "Hermit Crabs can Be Intresting Petts in Leeipzig",
            "Warrsaw and Arequuipa and Bakersfield and Belize and USA",
            "A Colorful center where we organize and travel to Belize.",
            "A Parrot Make a Colorful Pett in Cairns.",
            "A Parrot Make a Colorful Pett in [[MS_NAME_0001]].",
        )
        samples.forEach { sample ->
            val repaired = router.deterministicRepair(sample)
            Log.i(TAG, "in=[$sample]")
            Log.i(TAG, "out=[$repaired]")
            Log.i(TAG, "changed=${repaired != sample}")
        }
        router.releaseResources()
    }

    private companion object {
        const val TAG = "MANUSCRIPT_REPAIR_PROBE"
    }
}
