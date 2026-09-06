package tech.csalliance.unstuck.ui.assistant

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tech.csalliance.unstuck.design.component.SheetHandle
import tech.csalliance.unstuck.design.component.SheetScrim
import tech.csalliance.unstuck.design.theme.UTheme

/**
 * The get-to-know-you interview as a bottom sheet (the mobile home for what
 * the web / iOS render inline in the gateway card). [firstName] greets;
 * [onDismiss] fires after "I'm done" / "That's me set up", after the chevron
 * parks it (resumable at the parked step), and on a swipe-down — which also
 * only parks: nothing is marked done unless the user says so or reaches the
 * end. The host opens it when `InterviewAutoOpenGate` fires or from the pill.
 * [factCount] lets an untouched panel stand down when facts land from another
 * device a beat after it opened — see [InterviewFlow].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterviewSheet(host: InterviewHost, firstName: String?, onDismiss: () -> Unit, factCount: Int = 0) {
    val c = UTheme.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface, scrimColor = SheetScrim,
        dragHandle = { Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) { SheetHandle() } },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        ) {
            InterviewFlow(host = host, firstName = firstName, onFinished = onDismiss, onCollapse = onDismiss, factCount = factCount)
        }
    }
}
