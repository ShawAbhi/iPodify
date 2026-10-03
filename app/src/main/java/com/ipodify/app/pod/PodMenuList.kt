package com.ipodify.app.pod

import androidx.compose.runtime.Composable

/**
 * The menu screen. The playlist is shown as Cover Flow — see [PodCoverFlow];
 * the old striped list is in git history if it is ever wanted back.
 */
@Composable
fun PodMenuList(menuState: PodMenuState) {
    PodCoverFlow(menuState)
}
