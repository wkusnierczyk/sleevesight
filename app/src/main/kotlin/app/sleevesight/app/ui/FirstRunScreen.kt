package app.sleevesight.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.ui.home.PageCloseButton
import app.sleevesight.app.ui.home.PageFrame
import app.sleevesight.app.ui.theme.SleevesightTheme

/**
 * What Sleevesight reads, what leaves the phone and to whom (design, *Privacy and security*), the
 * way to notification access, and, last, the link to the privacy policy on the site, which says
 * more, with where the app stands under the GDPR and the EU AI Act (#295). [openAccessSettings]
 * returns false when no settings screen for it could be opened.
 *
 * During the first run ([onClose] null), it opens under the app's name, and once access is on,
 * *Continue* ([onContinue]) goes on. Opened again from somewhere ([onClose]: Home's *What leaves the
 * device?*, or its *Turn on notification access* once access is off, or the settings' *About*), it is
 * a page (#549): a bar with the back arrow and the page's name, *What leaves the device?*, and at its
 * end *Close*, which both go back to where it was opened from, as Android's back does; there is
 * nothing to continue, so no *Continue*, and its introduction leaves out the first run's "Before you
 * start".
 *
 * It opens at the top in touch mode. Out of touch mode (a keyboard or a D-pad), the first thing on it
 * that takes focus does so as it opens and is scrolled into view, so the focus shows: during the first
 * run, its one button, which comes before the link; opened again, the back arrow, at the top.
 */
@Composable
fun FirstRunScreen(
    accessGranted: Boolean,
    openAccessSettings: () -> Boolean,
    onContinue: () -> Unit,
    onClose: (() -> Unit)? = null,
) {
    if (onClose == null) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier =
                    Modifier
                        .safeDrawingPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(PADDING),
                verticalArrangement = Arrangement.spacedBy(SPACING),
            ) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                FirstRunText(R.string.first_run_intro, accessGranted, openAccessSettings, onContinue)
            }
        }
    } else {
        PageFrame(
            title = stringResource(R.string.home_what_leaves),
            onBack = onClose,
            containerColor = MaterialTheme.colorScheme.surface,
            padding = PADDING,
            spacing = SPACING,
        ) {
            FirstRunText(R.string.first_run_intro_again, accessGranted, openAccessSettings, onContinue = null)
            PageCloseButton(onClose, Modifier.padding(top = 8.dp))
        }
    }
}

private val PADDING = PaddingValues(horizontal = 24.dp, vertical = 16.dp)
private val SPACING = 12.dp

/**
 * The screen's text, from its introduction ([intro]), the way to notification access, and the link to
 * the privacy policy; once access is on, *Continue* ([onContinue]) when there is one.
 */
@Composable
private fun FirstRunText(
    @StringRes intro: Int,
    accessGranted: Boolean,
    openAccessSettings: () -> Boolean,
    onContinue: (() -> Unit)?,
) {
    var noSettingsScreen by rememberSaveable { mutableStateOf(false) }
    Text(stringResource(intro), style = MaterialTheme.typography.bodyLarge)

    Heading(R.string.first_run_reads_title)
    Body(R.string.first_run_reads)

    Heading(R.string.first_run_leaves_title)
    Bullet(R.string.first_run_to_sources)
    Bullet(R.string.first_run_to_ai)
    Bullet(R.string.first_run_api_keys)
    Bullet(R.string.first_run_to_art_host)
    Bullet(R.string.first_run_to_speech)
    Bullet(R.string.first_run_to_developer)
    Body(R.string.first_run_ip_address)

    Heading(R.string.first_run_stays_title)
    Body(R.string.first_run_stays)
    Body(R.string.first_run_no_tracking)

    if (accessGranted) {
        Heading(R.string.access_on)
        if (onContinue != null) {
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.first_run_continue))
            }
        }
    } else {
        Heading(R.string.access_off)
        Body(R.string.access_off_hint)
        Button(
            onClick = { noSettingsScreen = !openAccessSettings() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.open_access_settings))
        }
        if (noSettingsScreen) {
            Text(
                stringResource(R.string.no_access_settings),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    Body(R.string.first_run_privacy)
    PrivacyPolicyLink()
}

@Composable
private fun Heading(
    @StringRes text: Int,
) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun Body(
    @StringRes text: Int,
) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Bullet(
    @StringRes text: Int,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("•", style = MaterialTheme.typography.bodyMedium)
        Body(text)
    }
}

@Preview(showBackground = true, heightDp = 1400)
@Composable
private fun FirstRunAccessOffPreview() {
    SleevesightTheme { FirstRunScreen(accessGranted = false, openAccessSettings = { true }, onContinue = {}) }
}

@Preview(showBackground = true, heightDp = 1400)
@Composable
private fun FirstRunAccessOnPreview() {
    SleevesightTheme { FirstRunScreen(accessGranted = true, openAccessSettings = { true }, onContinue = {}) }
}

@Preview(showBackground = true, heightDp = 1400)
@Composable
private fun WhatLeavesReopenedPreview() {
    SleevesightTheme {
        FirstRunScreen(accessGranted = true, openAccessSettings = { true }, onContinue = {}, onClose = {})
    }
}
