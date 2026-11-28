// Modified for Ember AirReceiver (2026).
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.ui

import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import app.ember.airreceiver.R

/**
 * FragmentNavigator — Owns the nav panel selection and the content fragment swap.
 *
 * WHAT: Keeps track of which nav panel item is selected, highlights it and replaces the
 * fragment in the content container when the selection changes.
 *
 * WHY: [app.ember.airreceiver.AirReceiverActivity] used to do the navigation itself, which mixed the
 * Activity's view setup with the fragment transactions and pushed the class past the
 * 400-line limit of CONTRIBUTING.md. The navigator keeps the nav panel and the content
 * area in sync in one place.
 *
 * HOW: The Activity creates one navigator with its fragment manager and its two nav
 * panel items, calls [setupNavigation] to install the click listeners and afterwards
 * calls [showHome] or [showSettings] to switch the content.
 *
 * Example:
 *   val navigator = FragmentNavigator(supportFragmentManager, navItemHome, navItemSettings)
 *   navigator.setupNavigation()
 *   navigator.showHome()
 */
internal class FragmentNavigator(
    /** Fragment manager of the hosting Activity — performs the transaction. */
    private val fragmentManager: FragmentManager,
    /** The "Home" nav panel item. */
    private val navItemHome: TextView,
    /** The "Settings" nav panel item. */
    private val navItemSettings: TextView,
    private val onNavigate: () -> Unit = {}
) {

    /**
     * Index of the currently selected nav item (0 = Home, 1 = Settings).
     *
     * The index keeps a click on the already selected item from re-creating the
     * fragment, which would throw away the user's scroll position for nothing.
     */
    var selectedNavIndex: Int = 0
        private set

    /**
     * Sets up click listeners for the navigation panel items.
     * Also updates the visual selected state (text color) of the active item.
     */
    fun setupNavigation() {
        navItemHome.setOnClickListener {
            if (selectedNavIndex != 0) {
                showHome()
            }
        }
        navItemSettings.setOnClickListener {
            if (selectedNavIndex != 1) {
                showSettings()
            }
        }

        // Set initial selected state
        navItemHome.isSelected = true
        navItemSettings.isSelected = false
    }

    /** Shows the [HomeFragment] in the content area. */
    fun showHome() = navigateTo(HomeFragment(), navItemHome)

    /** Shows the [SettingsFragment] in the content area. */
    fun showSettings() = navigateTo(SettingsFragment(), navItemSettings)

    /**
     * Replaces the content_container fragment with [fragment] and updates
     * the nav panel selection highlight.
     *
     * @param fragment  The Fragment to show in the content area.
     * @param navItem   The nav panel TextView that was clicked (for highlight update).
     */
    private fun navigateTo(fragment: Fragment, navItem: TextView) {
        // A screen that was opened from another screen (the diagnostics screen) sits on
        // the back stack. The navigation panel is the way out of every screen, so its
        // entries are removed before the new screen is shown — otherwise the back
        // button would return to a screen the owner has already left.
        fragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)

        // Update nav highlight
        navItemHome.isSelected = navItem == navItemHome
        navItemSettings.isSelected = navItem == navItemSettings
        selectedNavIndex = if (navItem == navItemHome) 0 else 1
        onNavigate()

        // Replace fragment
        fragmentManager.beginTransaction()
            .replace(R.id.content_container, fragment)
            .commit()
    }
}
