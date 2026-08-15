package iompar.mpts.ie

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * The policy text itself lives in tfi-app/PRIVACY_POLICY.md and is published to this URL by
 * scripts/gen-privacy-policy-html.py. The app deliberately carries no copy of it: two copies drift,
 * and the one users are shown would then be whatever shipped in their build rather than the current
 * policy. Play's requirement that the policy be reachable from inside the app is met by this link.
 *
 * Not part of BackendConfig — pointing the app at a different backend does not change whose policy
 * governs this app, so the URL stays fixed even when the API root does not.
 */
const val PRIVACY_POLICY_URL = "https://iompar.mpts.ie/privacy-policy"

/** Opens the hosted privacy policy in the user's browser. */
fun openPrivacyPolicy(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // A device with no browser is unusual but possible (kiosk images, stripped ROMs). Failing
        // silently would look like a dead button, so say where the policy is instead.
        Toast.makeText(context, PRIVACY_POLICY_URL, Toast.LENGTH_LONG).show()
    }
}
