package io.github.lightheaded.lugu

import android.Manifest
import android.os.Build
import androidx.test.rule.GrantPermissionRule

/**
 * Grants the notification permission before the activity under test launches.
 *
 * lugu asks for it on first launch, deliberately — the media notification *is* the player
 * for most of a listening session, so waiting until something plays would mean the first
 * book plays with no controls on the lock screen. On a phone that is a one-off dialog. On a
 * freshly created emulator it happens during every single test run, and the system's
 * permission dialog is a separate activity that comes up over the app: `MainActivity`
 * reaches RESUMED, is paused behind `GrantPermissionsActivity`, and Compose reports "no
 * compose hierarchies found in the app" — which reads as the app having failed to start,
 * which is the one thing these tests exist to detect. Granting it up front removes the
 * dialog without changing what is being tested.
 *
 * It only exists from Android 13, so below that this grants nothing and does nothing —
 * which is why the API 26 leg of the matrix never saw this.
 *
 * Use it as the outermost rule, so the grant happens before the activity is launched:
 *
 * ```
 * @get:Rule(order = 0)
 * val notifications = grantNotificationPermission()
 *
 * @get:Rule(order = 1)
 * val compose = createAndroidComposeRule<MainActivity>()
 * ```
 */
fun grantNotificationPermission(): GrantPermissionRule =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant()
    }
