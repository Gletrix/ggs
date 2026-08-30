package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.example.ui.PairingCodeEntryCard
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairingCodeEntryCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testInitialCardState_PairButtonDisabled() {
        composeTestRule.setContent {
            MyApplicationTheme {
                PairingCodeEntryCard(
                    onPairSubmit = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("pairing_code_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("submit_pair_button").assertIsNotEnabled()
    }

    @Test
    fun testEnteringSixDigits_EnablesPairButtonAndSubmits() {
        var submittedCode: String? = null

        composeTestRule.setContent {
            MyApplicationTheme {
                PairingCodeEntryCard(
                    onPairSubmit = { code ->
                        submittedCode = code
                    }
                )
            }
        }

        // Enter 6 digits
        composeTestRule.onNodeWithTag("pairing_code_input").performTextInput("123456")

        // Button should become enabled
        composeTestRule.onNodeWithTag("submit_pair_button").assertIsEnabled()

        // Click pair button
        composeTestRule.onNodeWithTag("submit_pair_button").performClick()

        // Verify callback was invoked with exact 6 digits
        assertEquals("123456", submittedCode)
    }

    @Test
    fun testEnteringNonDigits_FiltersCorrectly() {
        var submittedCode: String? = null

        composeTestRule.setContent {
            MyApplicationTheme {
                PairingCodeEntryCard(
                    onPairSubmit = { code ->
                        submittedCode = code
                    }
                )
            }
        }

        // Enter letters and symbols mixed with digits
        composeTestRule.onNodeWithTag("pairing_code_input").performTextInput("ab12cd34ef56gh78")

        // Pair button enabled because it takes first 6 digits (123456)
        composeTestRule.onNodeWithTag("submit_pair_button").assertIsEnabled()
        composeTestRule.onNodeWithTag("submit_pair_button").performClick()

        assertEquals("123456", submittedCode)
    }

    @Test
    fun testDismissButtonAndCloseIcon() {
        var dismissed = false

        composeTestRule.setContent {
            MyApplicationTheme {
                PairingCodeEntryCard(
                    onPairSubmit = {},
                    onDismiss = { dismissed = true }
                )
            }
        }

        composeTestRule.onNodeWithTag("card_close_button").assertIsDisplayed().performClick()
        assertTrue(dismissed)
    }
}
