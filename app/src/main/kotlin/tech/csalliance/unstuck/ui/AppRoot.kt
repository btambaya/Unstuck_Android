package tech.csalliance.unstuck.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import tech.csalliance.unstuck.AppGraph
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.auth.AuthScreen

@Composable
fun AppRoot(graph: AppGraph) {
    val vm: AppViewModel = viewModel(factory = viewModelFactory { initializer { AppViewModel(graph) } })

    if (!vm.configured) {
        SetupScreen()
        return
    }

    val authed by vm.authed.collectAsStateWithLifecycle()
    when (authed) {
        null -> LoadingScreen()
        false -> AuthScreen(vm)
        true -> MainScaffold(vm)
    }
}

@Composable
private fun LoadingScreen() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { CircularProgressIndicator(color = UTheme.colors.coralDeep) }
}

@Composable
private fun SetupScreen() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SectionLabel("Setup")
        Text(
            "Add SUPABASE_ANON_KEY to secrets.properties, then rebuild.",
            modifier = Modifier.padding(top = 12.dp),
            style = UFont.sans(15),
            color = UTheme.colors.ink2,
            textAlign = TextAlign.Center,
        )
        Text(
            "supabase projects api-keys --project-ref uaxfteluwctrlgwmmfzi",
            modifier = Modifier.padding(top = 16.dp),
            style = UFont.mono(12),
            color = UTheme.colors.ink3,
            textAlign = TextAlign.Center,
        )
    }
}
