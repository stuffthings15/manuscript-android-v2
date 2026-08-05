package com.echolink.mobile.fixture

import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixtureRenderedTriggerRealCropTest {
    @Test fun encodedOfficialSettingsReturnTriggerIsPresent() {
        val image = load("settings-return-official-trigger.png")
        val result = evaluate(image)

        assertTrue(result.reasons.joinToString(), result.present)
    }

    @Test fun encodedFixtureBeforeTriggerIsAbsent() {
        assertFalse(evaluate(load("settings-return-before-trigger.png")).present)
    }

    @Test fun encodedSettingsBackgroundIsAbsent() {
        assertFalse(evaluate(load("settings-background.png")).present)
    }

    @Test fun encodedPanelReturnWithoutTriggerIsAbsent() {
        assertFalse(evaluate(load("panel-return-without-trigger.png")).present)
    }

    private fun evaluate(image: BufferedImage): FixtureRenderedTriggerProbe.Result {
        val pixels = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        return FixtureRenderedTriggerProbe.evaluate(pixels, image.width, image.height)
    }

    private fun load(name: String): BufferedImage = requireNotNull(
        javaClass.getResource("/rendered-settings/$name"),
    ).openStream().use(ImageIO::read)
}
